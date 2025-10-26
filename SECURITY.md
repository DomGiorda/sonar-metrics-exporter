## Security Notice / Aviso de Seguridad

This plugin is designed to export SonarQube metrics to Prometheus format. Here's what it does:

- Creates a web endpoint at `/api/prometheus/metrics`
- Reads metric data from SonarQube's internal API
- Exposes these metrics in Prometheus format
- No external connections are made except responding to Prometheus scrapes
- All code is open source and can be audited

### Permissions / Permisos
The plugin requires:
- Read access to SonarQube metrics
- Permission to create a web endpoint (standard SonarQube plugin API)
- No file system access
- No network access except responding to HTTP requests

### Verification / Verificación
1. The source code is open and available for audit
2. The plugin is built using Maven with reproducible builds
3. You can verify the JAR contents:
   ```powershell
   # View JAR contents
   jar tvf target/sonar-prometheus-exporter-*.jar
   
   # Calculate SHA256 hash
   Get-FileHash target/sonar-prometheus-exporter-*.jar -Algorithm SHA256
   ```

4. Compare the hash with the one from the release page

### False Positive Analysis / Análisis de Falsos Positivos
Some antiviruses may flag this plugin because:
- It creates a web endpoint
- It reads system metrics
- It responds to network requests

These are normal and necessary behaviors for a Prometheus exporter. The plugin:
- Only uses official SonarQube APIs
- Is completely open source
- Makes no outbound connections
- Only reads metric data that's already public in SonarQube UI

### Build from Source / Construir desde Fuente
To ensure security, you can build the plugin from source:
```powershell
git clone https://github.com/DomGiorda/sonar-metrics-exporter
cd sonar-metrics-exporter
mvn clean package
```

The resulting JAR in `target/` will be functionally identical to the release version.