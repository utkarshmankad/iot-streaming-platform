"""Cookie and CSRF support for isolated stack checks."""

import json
from http.cookiejar import CookieJar
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import HTTPCookieProcessor, Request, build_opener


class Browser:
    def __init__(self, credentials, port, operator="a"):
        env = dict(line.split("=", 1) for line in Path(credentials).read_text().splitlines()
                   if "=" in line)
        self.base = f"http://127.0.0.1:{int(port)}/api/v1"
        self.opener = build_opener(HTTPCookieProcessor(CookieJar()))
        code, _ = self.post("/session", {"username": f"operator-{operator}",
                                         "password": env[f"IOT_STACK_OPERATOR_{operator.upper()}_PASSWORD"]})
        assert code == 200, f"Isolated tenant login failed: {code}"

    def call(self, path, method="GET", data=None, headers=None):
        body = json.dumps(data).encode() if data is not None else None
        request = Request(self.base + path, data=body, method=method,
                          headers={"Content-Type": "application/json", **(headers or {})})
        try:
            with self.opener.open(request, timeout=10) as result:
                return result.status, json.load(result)
        except HTTPError as error:
            return error.code, json.load(error)

    def post(self, path, data, headers=None):
        code, csrf = self.call("/session/csrf")
        assert code == 200, f"Could not obtain CSRF token: {code}"
        return self.call(path, "POST", data, {csrf["headerName"]: csrf["token"], **(headers or {})})
