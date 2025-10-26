# Release Notes - Version 2.0.0

## Major Features
- **Severity Label Support**: Added `severity` label to all exported metrics, enabling filtering by BLOCKER, CRITICAL, MAJOR, MINOR, and INFO levels in Prometheus/Grafana.
- **Dynamic Severity Detection**: Automatically detects and exports severity-specific metrics (e.g., `blocker_violations`, `critical_vulnerabilities`).
- **Enhanced SonarQube Compatibility**: Verified support for SonarQube versions 9.x through 25.10.

## New Features
- Added Docker Compose setup for easy local testing:
  - `docker-compose.yml` — spins up SonarQube + Prometheus
  - `prometheus/prometheus.yml` — pre-configured scraping
- Added comprehensive test suite for severity label functionality
- Improved documentation with PromQL examples for severity filtering

## Breaking Changes
- Metrics now include a third label `severity`. Update your Grafana dashboards if they assume exactly two labels.
- Default severity "ALL" for total metrics without severity specification.

## Querying Examples
Filter metrics by severity in Prometheus/Grafana:
```promql
# Blocker bugs by repository
sum by (key, name) (sonarqube_bugs{severity="BLOCKER"})

# Critical vulnerabilities
sum by (key, name) (sonarqube_vulnerabilities{severity="CRITICAL"})

# All severities for a metric
sum by (severity) (sonarqube_code_smells)
```

## Installation
1. Download the plugin JAR from releases
2. Place in `$SONARQUBE_HOME/extensions/plugins/`
3. Restart SonarQube

## Compatibility
- SonarQube: 9.x, 10.8, and 25.10
- Java: 17+
- Prometheus: 0.16+ (client)

## Docker Quick Start
```bash
# Build plugin
mvn -DskipTests package

# Copy to plugins directory
mkdir -p plugins
cp target/*.jar plugins/

# Start stack (SonarQube + Prometheus)
docker-compose up -d
```

## Documentation
- Added examples in `README.md`
- New Prometheus configuration examples in `./resources/prometheus/`
- Updated Grafana dashboard template in `resources/grafana_dashboard.json`

## Contributors
Thanks to:
- @DWS-guy for suggesting the severity label feature (#1)