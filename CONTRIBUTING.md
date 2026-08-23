# Contributing to Hyperledger Besu On-Chain Permissioning Plugin

Thank you for your interest in contributing to the Hyperledger Besu On-Chain Permissioning Plugin!

## Code of Conduct

This project adheres to the Hyperledger Code of Conduct. By participating, you are expected to uphold this code.

## How to Contribute

1. **Fork & Clone:** Fork the repository and clone your fork locally.
2. **Branching:** Create a feature branch off `main` (e.g., `feature/my-improvement`).
3. **Coding Standard:** Ensure your Java code adheres to Google Java Format standards. You can format your code automatically using:
   ```bash
   ./gradlew spotlessApply
   ```
4. **Testing:** All pull requests must include unit and integration tests covering new or modified functionality:
   ```bash
   ./gradlew test
   ```
5. **Licensing & DCO:** All commits must include a `Signed-off-by:` line (Developer Certificate of Origin) indicating acceptance of the Apache 2.0 license:
   ```bash
   git commit -s -m "feat: add support for custom gas limits"
   ```

## Pull Request Guidelines

- Keep pull requests focused on a single change or feature.
- Ensure all CI build and static analysis checks pass cleanly.
- Provide a clear PR description explaining what was changed and why.
