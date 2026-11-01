# Security policy

Please report vulnerabilities privately through GitHub's "Report a vulnerability" (Security → Advisories) on this repository. Don't open public issues. Include affected versions, platform builds, configuration (with keys removed) and reproduction steps. We aim to acknowledge reports within 72 hours.

In scope: authentication bypass, identity spoofing, replay, credential disclosure, fail-open behaviour, and resource exhaustion in NetworkLock code. Out of scope: volumetric DDoS, attacks that require an attacker on the proxy-backend link (documented in [the threat model](docs/THREAT-MODEL.md)), and vulnerabilities in the server software itself.

Never include real keys in reports. If a key may have leaked, rotate it as described in [CONFIGURATION.md](docs/CONFIGURATION.md#key-rotation-no-downtime).
