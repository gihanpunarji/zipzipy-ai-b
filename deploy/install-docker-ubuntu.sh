#!/usr/bin/env bash
# Run on a new Ubuntu VM, not on your development Mac.
set -euo pipefail
source /etc/os-release
[[ "$ID" == ubuntu ]] || { echo 'This installer requires Ubuntu.' >&2; exit 1; }
if command -v docker >/dev/null && docker compose version >/dev/null 2>&1; then
  echo 'Docker and Compose are already installed.'
  exit 0
fi
sudo apt-get update
sudo apt-get install -y ca-certificates curl python3 git
sudo install -m 0755 -d /etc/apt/keyrings
sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
sudo chmod a+r /etc/apt/keyrings/docker.asc
sudo tee /etc/apt/sources.list.d/docker.sources >/dev/null <<EOF
Types: deb
URIs: https://download.docker.com/linux/ubuntu
Suites: ${UBUNTU_CODENAME:-$VERSION_CODENAME}
Components: stable
Architectures: $(dpkg --print-architecture)
Signed-By: /etc/apt/keyrings/docker.asc
EOF
sudo apt-get update
sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
sudo systemctl enable --now docker
echo 'Docker installed. Use sudo bash deploy/deploy.sh, or explicitly configure Docker group access.'
