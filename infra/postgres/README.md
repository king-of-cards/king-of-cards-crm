# Local PostgreSQL DEV databases

The backend DEV profile targets these local PostgreSQL 17 databases and roles:

| Service | Database | Role | Host | Port |
| --- | --- | --- | --- | ---: |
| Identity Service | `koc_identity_dev` | `koc_identity_dev_app` | `localhost` | 5432 |
| CRM Service | `koc_crm_dev` | `koc_crm_dev_app` | `localhost` | 5432 |

The databases and passwords are managed locally. Do not put passwords in this file, source code, or Git. Configure `IDENTITY_DB_USERNAME`, `IDENTITY_DB_PASSWORD`, `CRM_DB_USERNAME`, and `CRM_DB_PASSWORD` in the respective STS launch configurations. `POSTGRES_HOST` and `POSTGRES_PORT` default to `localhost` and `5432`.

For a local connection check, run these commands in PowerShell; `-W` prompts for the password without placing it in command history:

```powershell
& 'C:\Program Files\PostgreSQL\17\bin\psql.exe' -h localhost -p 5432 -U koc_identity_dev_app -d koc_identity_dev -W -c "SELECT current_database(), current_user;"
& 'C:\Program Files\PostgreSQL\17\bin\psql.exe' -h localhost -p 5432 -U koc_crm_dev_app -d koc_crm_dev -W -c "SELECT current_database(), current_user;"
```

Only the `dev` profile contains datasource and Flyway settings. No production datasource or production profile is configured.