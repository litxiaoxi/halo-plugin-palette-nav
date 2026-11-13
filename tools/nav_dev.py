"""Shared helpers for the local-development scripts under `tools/`.

The verification scripts talk to a running Halo instance (default
`http://localhost:8095`) through the core ConfigMap REST API with basic auth.
Keeping the base URL, credentials and request plumbing here means changing a
port or the default dev password only requires editing one file.
"""

from __future__ import annotations

import base64
import json
import os
import urllib.request

DEFAULT_BASE_URL = os.environ.get("HALO_URL", "http://localhost:8095")
DEFAULT_USERNAME = os.environ.get("HALO_USERNAME", "admin")
DEFAULT_PASSWORD = os.environ.get("HALO_PASSWORD", "admin123")
CONFIGMAP_NAME = "palette-nav-config"


def basic_auth_header(username: str = DEFAULT_USERNAME,
                      password: str = DEFAULT_PASSWORD) -> str:
    token = base64.b64encode(f"{username}:{password}".encode("utf-8")).decode("ascii")
    return "Basic " + token


class HaloClient:
    """Minimal REST client for the local dev instance."""

    def __init__(self, base_url: str = DEFAULT_BASE_URL,
                 username: str = DEFAULT_USERNAME,
                 password: str = DEFAULT_PASSWORD) -> None:
        self.base_url = base_url.rstrip("/")
        self.auth = basic_auth_header(username, password)

    def call(self, method: str, path: str, body=None) -> bytes:
        request = urllib.request.Request(
            self.base_url + path,
            method=method,
            data=json.dumps(body).encode("utf-8") if body is not None else None,
            headers={"Authorization": self.auth, "Content-Type": "application/json"},
        )
        with urllib.request.urlopen(request) as response:
            return response.read()

    def get_configmap(self, name: str = CONFIGMAP_NAME) -> dict:
        return json.loads(self.call("GET", f"/api/v1alpha1/configmaps/{name}"))

    def put_configmap(self, configmap: dict, name: str = CONFIGMAP_NAME) -> dict:
        return json.loads(self.call("PUT", f"/api/v1alpha1/configmaps/{name}", configmap))
