package org.evsyukov.hermes.ui.setup

import org.evsyukov.hermes.data.server.ProxyType

/**
 * Whether this build can carry the traffic of a server over [type]. The embedded tailnet needs its
 * Go library in `app/libs/tailnet.aar`; a build without it reports the node unavailable, and no
 * state of that node (stopped, starting, error) makes the route available.
 */
internal fun routeSupported(type: ProxyType, tailnetAvailable: Boolean): Boolean =
    type != ProxyType.TAILSCALE || tailnetAvailable

/**
 * Runs [check] only when this build can carry [type]; otherwise answers [blocked] without touching
 * the node. The guard sits here, before any engine start or wait for the proxy of the node.
 */
internal suspend fun guardedCheck(
    type: ProxyType,
    tailnetAvailable: Boolean,
    blocked: String,
    check: suspend () -> String,
): String = if (routeSupported(type, tailnetAvailable)) check() else blocked
