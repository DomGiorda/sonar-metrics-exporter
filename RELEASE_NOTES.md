# SonarQube metrics Exporter - Release Notes

## Version 3.0.0

[![Build Status](https://github.com/DomGiorda/sonar-metrics-exporter/actions/workflows/build.yml/badge.svg)](https://github.com/DomGiorda/sonar-metrics-exporter/actions)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Java Version](https://img.shields.io/badge/Java-17%2B-orange.svg)](https://www.oracle.com/java/)
[![SonarQube API](https://img.shields.io/badge/SonarQube-10.8_%7C_25.10_%7C_2026.x-brightgreen.svg)](https://www.sonarqube.org/)

---

### Executive Summary

Version **3.0.0** introduces a major architectural evolution of the **SonarQube metrics Exporter Plugin**. This release establishes explicit multi-version matrix compilation for contemporary SonarQube ecosystems (from 10.8 through 2026.x), refined Prometheus metric label dimensions including severity breakdown, optimized distribution via GitHub Packages, and bundled Grafana dashboards.

---

### Key Improvements

- **Multi-Version Profile Architecture**: Maven build matrix now compiles version-tailored artifacts optimized for specific SonarQube API baselines (10.8, 25.10, and 2026.x).
- **Severity-Aware Prometheus Metrics**: Multi-dimensional label support (`key`, `name`, `severity`) across code quality, vulnerability, and debt metrics.
- **Dedicated Artifact Naming**: Standardized artifact distribution naming preventing package collision across different SonarQube target releases.
- **Deprecation of Legacy 9.x API**: Deprecated SonarQube 9.x support to leverage Java 17 runtime features and modern SonarQube Web API endpoints.
- **Turnkey Infrastructure**: Complete local development stack with pre-configured Docker Compose (SonarQube + Prometheus + Grafana).

---

### Target Compatibility & Distribution Matrix

| Profile | Target SonarQube Version | Artifact Name | Target Runtime | Primary Distribution |
| :--- | :--- | :--- | :--- | :--- |
| `sq-26x` | SonarQube 2026.x (Latest / Mainstream) | `sonar-metrics-exporter.jar` | Java 17+ | Default Package Release |
| `sq-25x` | SonarQube 25.10 LTS | `sonar-metrics-exporter-sq25.10.jar` | Java 17+ | GitHub Packages / Assets |
| `sq-10x` | SonarQube 10.8 LTS | `sonar-metrics-exporter-sq10.8.jar` | Java 17+ | GitHub Packages / Assets |

> [!NOTE]
> The default artifact `sonar-metrics-exporter.jar` (without version suffix) is targeted for the latest SonarQube 2026.x API release. For legacy LTS deployments, select the artifact matching your target SonarQube major version.

---

### Metrics & Label Architecture

The exporter exposes SonarQube component measures in standard Prometheus text format under the `sonarqube_` metric namespace.

#### Exported Metric Reference

| Metric Identifier | Description | Exported Labels |
| :--- | :--- | :--- |
| `sonarqube_bugs` | Total open bug count | `key`, `name`, `severity` |
| `sonarqube_vulnerabilities` | Total security vulnerability count | `key`, `name`, `severity` |
| `sonarqube_code_smells` | Total maintainability code smell count | `key`, `name`, `severity` |
| `sonarqube_security_hotspots` | Security hotspots needing review | `key`, `name` |
| `sonarqube_coverage` | Code coverage percentage (0 - 100) | `key`, `name` |
| `sonarqube_duplicated_lines_density` | Duplicated code density percentage | `key`, `name` |
| `sonarqube_ncloc` | Non-comment lines of code | `key`, `name` |
| `sonarqube_sqale_index` | Technical debt (expressed in minutes) | `key`, `name` |
| `sonarqube_reliability_rating` | SonarQube reliability rating grade | `key`, `name` |
| `sonarqube_security_rating` | SonarQube security rating grade | `key`, `name` |
| `sonarqube_sqale_rating` | SonarQube maintainability rating grade | `key`, `name` |

---

### PromQL Usage Showcase

#### Blocker & Critical Bugs Aggregated by Project
```promql
sum by (key, name) (
  sonarqube_bugs{severity=~"BLOCKER|CRITICAL"}
)
```

#### Code Coverage Threshold Monitoring
```promql
sonarqube_coverage < 80
```

#### Total Technical Debt Across Organization (in Hours)
```promql
sum(sonarqube_sqale_index) / 60
```

#### Security Vulnerabilities Breakdown by Severity
```promql
sum by (severity) (
  sonarqube_vulnerabilities
)
```

---

### Breaking Changes & Migration Guide

> [!IMPORTANT]
> **Java 17+ Requirement**: SonarQube Exporter 3.0.0 requires Java 17 or higher on the SonarQube host instance.
> **Metric Label Schema**: If existing Prometheus recording rules or Grafana dashboards query metrics without aggregation (`by (key, name)`), update them to handle the optional `severity` label.

1. **Upgrading from 2.x**:
   - Stop your SonarQube instance.
   - Remove legacy JARs from `$SONARQUBE_HOME/extensions/plugins/`.
   - Download the compatible 3.0.0 JAR for your SonarQube version (e.g., `sonar-metrics-exporter-sq25.10.jar`).
   - Place the JAR into `$SONARQUBE_HOME/extensions/plugins/`.
   - Restart SonarQube.

---

### Quickstart Installation

#### Manual Installation

```bash
# 1. Download artifact (example for SonarQube 2026.x)
wget https://github.com/DomGiorda/sonar-metrics-exporter/releases/download/v3.0.0/sonar-metrics-exporter.jar

# 2. Move to SonarQube plugins directory
mv sonar-metrics-exporter.jar /opt/sonarqube/extensions/plugins/

# 3. Restart SonarQube daemon
systemctl restart sonarqube
```

#### Docker Environment Setup

```bash
# Build plugin locally
mvn package -DskipTests

# Start local SonarQube + Prometheus stack
docker-compose up -d
```

---

### Verification & Endpoint Checks

To verify that the exporter is actively running on your SonarQube instance:

```bash
curl -s http://localhost:9000/api/prometheus/metrics | head -n 30
```

Expected response output:

```text
# HELP sonarqube_ncloc Non Comment Lines of Code
# TYPE sonarqube_ncloc gauge
sonarqube_ncloc{key="my-project-key",name="My Project"} 12450.0
# HELP sonarqube_bugs Bugs
# TYPE sonarqube_bugs gauge
sonarqube_bugs{key="my-project-key",name="My Project",severity="BLOCKER"} 0.0
sonarqube_bugs{key="my-project-key",name="My Project",severity="CRITICAL"} 2.0
```

---

### Grafana Dashboard Integration

This release includes a ready-to-use Grafana dashboard template:
- Path: `resources/grafana_dashboard.json`
- Panels included: Overall Health Scorecards, Security Ratings, Test Coverage Gauges, Technical Debt Trends, and Severity Breakdown Tables.

To import:
1. Open Grafana UI -> **Dashboards** -> **Import**.
2. Upload `resources/grafana_dashboard.json` or paste its JSON content.
3. Select your Prometheus data source and click **Import**.

---

### Contributors

Special thanks to all contributors and community members who reported issues, suggested improvements, and provided feedback for the 3.0.0 release cycle.