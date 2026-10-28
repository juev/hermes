"""Web Push for UnifiedPush endpoints: VAPID (RFC 8292) and aes128gcm encryption (RFC 8291).

Only the ``cryptography`` package is needed; the HTTP request goes through urllib.
"""

from __future__ import annotations

import base64
import json
import os
import struct
import time
import urllib.error
import urllib.parse
import urllib.request

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.asymmetric.utils import decode_dss_signature
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF


def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


def unb64url(text: str) -> bytes:
    return base64.urlsafe_b64decode(text + "=" * (-len(text) % 4))


def _public_bytes(key: ec.EllipticCurvePublicKey) -> bytes:
    return key.public_bytes(serialization.Encoding.X962, serialization.PublicFormat.UncompressedPoint)


class Vapid:
    """The server's P-256 key: the public half goes to the phone, the private half signs requests."""

    def __init__(self, private_key: ec.EllipticCurvePrivateKey):
        self.private_key = private_key

    @classmethod
    def generate(cls) -> "Vapid":
        return cls(ec.generate_private_key(ec.SECP256R1()))

    @classmethod
    def from_pem(cls, pem: bytes) -> "Vapid":
        return cls(serialization.load_pem_private_key(pem, password=None))

    def pem(self) -> bytes:
        return self.private_key.private_bytes(
            serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption())

    @property
    def public_key(self) -> str:
        """The application server key in the form UnifiedPush and browsers take: base64url, uncompressed."""
        return b64url(_public_bytes(self.private_key.public_key()))

    def authorization(self, endpoint: str, subject: str, ttl: int = 12 * 3600) -> str:
        parts = urllib.parse.urlsplit(endpoint)
        claims = {"aud": f"{parts.scheme}://{parts.netloc}", "exp": int(time.time()) + ttl, "sub": subject}
        header = b64url(json.dumps({"typ": "JWT", "alg": "ES256"}, separators=(",", ":")).encode())
        body = b64url(json.dumps(claims, separators=(",", ":")).encode())
        signing_input = f"{header}.{body}".encode()
        r, s = decode_dss_signature(self.private_key.sign(signing_input, ec.ECDSA(hashes.SHA256())))
        token = f"{header}.{body}.{b64url(r.to_bytes(32, 'big') + s.to_bytes(32, 'big'))}"
        return f"vapid t={token}, k={self.public_key}"


def encrypt(payload: bytes, p256dh: str, auth: str, *, salt: bytes | None = None,
            server_key: ec.EllipticCurvePrivateKey | None = None, record_size: int = 4096) -> bytes:
    """The aes128gcm body of RFC 8291 for one record: header, then the encrypted, padded payload."""
    user_public = unb64url(p256dh)
    user_key = ec.EllipticCurvePublicKey.from_encoded_point(ec.SECP256R1(), user_public)
    auth_secret = unb64url(auth)
    salt = salt or os.urandom(16)
    server_key = server_key or ec.generate_private_key(ec.SECP256R1())
    server_public = _public_bytes(server_key.public_key())

    shared = server_key.exchange(ec.ECDH(), user_key)
    key_info = b"WebPush: info\x00" + user_public + server_public
    ikm = HKDF(algorithm=hashes.SHA256(), length=32, salt=auth_secret, info=key_info).derive(shared)
    cek = HKDF(algorithm=hashes.SHA256(), length=16, salt=salt, info=b"Content-Encoding: aes128gcm\x00").derive(ikm)
    nonce = HKDF(algorithm=hashes.SHA256(), length=12, salt=salt, info=b"Content-Encoding: nonce\x00").derive(ikm)

    if len(payload) + 1 + 16 > record_size:
        raise ValueError("payload does not fit one record")
    # 0x02 marks the last record; no further padding.
    ciphertext = AESGCM(cek).encrypt(nonce, payload + b"\x02", None)
    header = salt + struct.pack("!IB", record_size, len(server_public)) + server_public
    return header + ciphertext


class PushGone(Exception):
    """The push service no longer knows the endpoint (404 or 410): the subscription is to be dropped."""


def send(endpoint: str, p256dh: str, auth: str, payload: dict, vapid: Vapid, subject: str,
         ttl: int = 3600, urgency: str = "high", timeout: float = 15.0) -> int:
    """Posts one encrypted message; returns the HTTP status, raises PushGone for a dead endpoint."""
    body = encrypt(json.dumps(payload, ensure_ascii=False).encode(), p256dh, auth)
    request = urllib.request.Request(endpoint, data=body, method="POST", headers={
        "Content-Encoding": "aes128gcm",
        "Content-Type": "application/octet-stream",
        "TTL": str(ttl),
        "Urgency": urgency,
        "Authorization": vapid.authorization(endpoint, subject),
    })
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return response.status
    except urllib.error.HTTPError as error:
        if error.code in (404, 410):
            raise PushGone(endpoint) from error
        raise
