# Security Policy

This project handles venture capital fund workflows -- LP subscriptions,
portfolio-company due diligence, and capital movement. Treat vulnerabilities
as potentially high impact even when the demo data is synthetic.

## Do Not Disclose Publicly

Report privately before opening public issues for:

- credential exposure
- real LP or portfolio-company data exposure
- authorization bypass
- InvestmentCommitteeGovernor bypass
- audit-ledger tampering
- over-disclosure in reports or exports

## Reporting

Use GitHub private vulnerability reporting when available for the
repository. If that is unavailable, contact the repository maintainers
through the cloud-itonami organization before publishing details.

Include:

- affected commit or version
- reproduction steps
- expected and actual behavior
- impact on LP data, investment-decision enforcement or audit logging
- suggested fix, if known

## Production Guidance

- Store secrets outside Git.
- Keep real LP/portfolio-company data outside this repository.
- Run policy tests before deployment.
- Export and review audit logs regularly.
- Use least privilege for operators and service accounts.
