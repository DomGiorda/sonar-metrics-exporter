# Contributing Guide

This document outlines security practices and guidelines for contributing to the SonarQube Prometheus Exporter plugin.

## Security Guidelines

When making changes, ensure:

1. No outbound network connections
   - The plugin should only respond to incoming Prometheus scrapes
   - Use only SonarQube's internal API for data access

2. Minimal permissions
   - Use only the necessary SonarQube APIs
   - Don't access filesystem except through SonarQube APIs
   - Don't execute external commands

3. Code practices
   - No dynamic class loading
   - No reflection except in tests
   - No Runtime.exec() or ProcessBuilder
   - Use immutable objects where possible
   - Validate all inputs

4. Documentation
   - Document security-relevant behavior
   - Explain permissions needed
   - Note any changes to network/API access

## Building and Testing

1. Clean build from source:
   ```powershell
   mvn clean package
   ```

2. Verify JAR contents:
   ```powershell
   jar tvf target/sonar-prometheus-exporter-*.jar
   ```

3. Run all tests:
   ```powershell
   mvn verify
   ```

## Release Process

1. Update version in pom.xml
2. Run full test suite
3. Build with clean environment
4. Generate and publish SHA256 hash
5. Document all changes affecting security/permissions

## Code Review Checklist

- [ ] No new outbound network access
- [ ] Only uses documented SonarQube APIs
- [ ] Input validation on all parameters
- [ ] No unnecessary permissions
- [ ] Security implications documented
- [ ] Tests cover new/modified code