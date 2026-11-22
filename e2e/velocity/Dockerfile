FROM eclipse-temurin:25-jre-alpine

RUN apk add --no-cache bash curl jq

WORKDIR /opt/velocity

# Always fetch the latest Velocity 4.x build from PaperMC Fill v3 API and record exact version metadata
RUN set -euo pipefail; \
    UA="VelocityPteroPower-E2E/1.0 (https://github.com/Tubs-Pluginz/VelocityPteroPower)"; \
    PROJ_JSON="$(curl -fsSL -H "User-Agent: ${UA}" "https://fill.papermc.io/v3/projects/velocity")"; \
    VEL_VERSION="$(printf '%s' "$PROJ_JSON" | jq -r '[.versions["4.0.0"][] | select(endswith("-SNAPSHOT") | not)] | first // .versions["4.0.0"][0]')"; \
    BUILD_JSON="$(curl -fsSL -H "User-Agent: ${UA}" "https://fill.papermc.io/v3/projects/velocity/versions/${VEL_VERSION}/builds/latest")"; \
    VEL_BUILD="$(printf '%s' "$BUILD_JSON" | jq -r '.id')"; \
    VEL_FILE="$(printf '%s' "$BUILD_JSON" | jq -r '.downloads["server:default"].name')"; \
    VEL_SHA256="$(printf '%s' "$BUILD_JSON" | jq -r '.downloads["server:default"].checksums.sha256')"; \
    VEL_URL="$(printf '%s' "$BUILD_JSON" | jq -r '.downloads["server:default"].url')"; \
    echo "Downloading Velocity ${VEL_VERSION} build #${VEL_BUILD} (${VEL_FILE})..."; \
    curl -fsSL -H "User-Agent: ${UA}" -o /opt/velocity/velocity.jar "$VEL_URL"; \
    JAVA_VER="$(java -version 2>&1 | head -n1)"; \
    jq -n \
      --arg version "$VEL_VERSION" \
      --arg build "$VEL_BUILD" \
      --arg file "$VEL_FILE" \
      --arg sha256 "$VEL_SHA256" \
      --arg url "$VEL_URL" \
      --arg java "$JAVA_VER" \
      '{version: $version, build: ($build | tonumber), file: $file, sha256: $sha256, downloadUrl: $url, javaVersion: $java}' \
      > /opt/velocity/velocity-version.json

COPY entrypoint.sh /opt/velocity/entrypoint.sh
RUN chmod +x /opt/velocity/entrypoint.sh

WORKDIR /server
EXPOSE 25577 19090

ENTRYPOINT ["/opt/velocity/entrypoint.sh"]
