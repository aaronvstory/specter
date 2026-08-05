import os, sys
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import pytest


@pytest.fixture(autouse=True)
def _reset_ipcheck_baseline_cache():
    """The direct-latency baseline is cached module-globally (so a bulk run measures it once), which would
    otherwise leak between tests and make them order-dependent. Clear it before every test."""
    try:
        from specter import ipcheck
        ipcheck._DIRECT_BASELINE["ms"] = None
        ipcheck._DIRECT_BASELINE["at"] = 0.0
    except Exception:
        pass
    yield
