Quick test setup: SonarQube + Prometheus

This repository contains a docker-compose setup to run SonarQube (Community) and Prometheus so you can test this Sonar plugin locally.

Files added
- `docker-compose.yml` — starts SonarQube and Prometheus (Prometheus scrapes `/api/prometheus/metrics`).
- `prometheus/prometheus.yml` — Prometheus config that scrapes SonarQube at `http://sonarqube:9000/api/prometheus/metrics`.

How to use
1. Build the plugin jar locally:

   (from repository root)

   ```powershell
   mvn -DskipTests package
   ```

   When build completes the plugin jar will be in `target/` (e.g. `target/sonar-prometheus-exporter-<version>.jar`).

2. Install the plugin into the Compose stack:

   - Create a `plugins/` directory at the repo root if it doesn't exist.
   - Copy the built jar into `./plugins/` (the compose mounts `./plugins` into SonarQube at `/opt/sonarqube/extensions/plugins`).

   ```powershell
   mkdir plugins -ErrorAction SilentlyContinue
   copy-item .\target\*.jar .\plugins\
   ```

   Note: SonarQube will only pick up new plugins on restart.

3. Start the stack:

   ```powershell
   docker-compose up -d
   ```

4. Wait for SonarQube to become healthy (logs indicate when ready). The web UI should be available at:
   - http://localhost:9000

5. Check Prometheus target page at http://localhost:9090/targets — it should show the `sonarqube` scrape target and a last scrape status.

6. Query metrics (Prometheus UI):
   - Example metric name exported by the plugin: `sonarqube_bugs{key="<projectKey>", name="<projectName>", severity="ALL"}`
   - Example PromQL to see totals: `sum by (key, name) (sonarqube_bugs)`

Notes and troubleshooting
- If SonarQube fails to start due to memory/ES issues, increase Docker memory or use a smaller SonarQube image/version. Edit `docker-compose.yml` and change `SONARQUBE_IMAGE` environment variable or the image line.
- If Prometheus cannot scrape metrics, ensure the plugin jar is present in `./plugins` and SonarQube was restarted after placing the jar.
- The compose uses `sonarqube:10.8-community` by default; change the `SONARQUBE_IMAGE` environment variable when invoking compose or update the `docker-compose.yml` if you want a different version.

Example: override image when starting
```powershell
$env:SONARQUBE_IMAGE = 'sonarqube:9.9-community'
docker-compose up -d
```
