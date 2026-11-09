"""Backend entry loaded by Hermes' documented dashboard plugin API loader."""
import importlib
import importlib.util
from pathlib import Path
import sys

_ROOT = Path(__file__).resolve().parents[1]
_PACKAGE = "hermes_android_backend"
if _PACKAGE not in sys.modules:
    spec = importlib.util.spec_from_file_location(_PACKAGE, _ROOT / "__init__.py", submodule_search_locations=[str(_ROOT)])
    if spec is None or spec.loader is None:
        raise RuntimeError("Cannot load the Android channel package")
    package = importlib.util.module_from_spec(spec)
    sys.modules[_PACKAGE] = package
    spec.loader.exec_module(package)
else:
    package = sys.modules[_PACKAGE]

registry, push = package.services()
api = importlib.import_module(_PACKAGE + ".api")
upstream = importlib.import_module(_PACKAGE + ".upstream")
router = api.make_router(registry, upstream.native_http, push=push)
