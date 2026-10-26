# King of Cards CRM Frontend

React + Vite foundation for the Cross-Selling CRM Beta. Open this `frontend/` folder independently in Visual Studio Code.

## Run locally

```powershell
Set-Location .\frontend
Copy-Item .env.example .env
npm ci
npm run dev
```

Open the Vite URL shown in the terminal (`http://localhost:5173`). Configure the API gateway URL in `.env` with `VITE_API_BASE_URL`; the local default is `http://localhost:8080`.

## Backend health integration

The Service status page calls the existing gateway routes:

- `GET /api/identity/health` → Identity Service `/health`
- `GET /api/crm/health` → CRM Service `/health`

Both return JSON with `service` and `status` fields. Start Eureka, Identity Service, CRM Service, then API Gateway before checking connectivity. The Gateway DEV configuration allows the Vite origin `http://localhost:5173`.

## Build

```powershell
npm run build
```
