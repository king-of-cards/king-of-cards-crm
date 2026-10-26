import { useCallback, useEffect, useState } from 'react';
import PageHeader from '../components/ui/PageHeader.jsx';
import ServiceStatusCard from '../components/status/ServiceStatusCard.jsx';
import { fetchServiceHealth, healthEndpoints } from '../services/healthService.js';

export default function ServiceStatusPage() {
  const [results, setResults] = useState({});
  const [loading, setLoading] = useState(true);

  const checkServices = useCallback(async () => {
    setLoading(true);
    const checks = await Promise.all(healthEndpoints.map(async (service) => {
      try {
        const data = await fetchServiceHealth(service);
        return [service.id, { ok: data?.status === 'UP', data }];
      } catch (error) {
        const message = error.code === 'ERR_NETWORK'
          ? 'Could not reach the API Gateway. Confirm the backend services are running.'
          : error.response
            ? `Gateway returned HTTP ${error.response.status}.`
            : 'The health request did not complete.';
        return [service.id, { ok: false, message }];
      }
    }));
    setResults(Object.fromEntries(checks));
    setLoading(false);
  }, []);

  useEffect(() => {
    checkServices();
  }, [checkServices]);

  return (
    <>
      <PageHeader
        eyebrow="Backend connectivity"
        title="Service status"
        description="Checks the existing Identity and CRM health endpoints through the API Gateway."
        action={(
          <button type="button" onClick={checkServices} disabled={loading} className="rounded-lg border border-white/15 px-4 py-2.5 text-sm font-semibold text-white transition hover:bg-white/5 disabled:cursor-wait disabled:opacity-50">
            {loading ? 'Checking…' : 'Refresh status'}
          </button>
        )}
      />
      <div className="grid gap-4 md:grid-cols-2">
        {healthEndpoints.map((service) => (
          <ServiceStatusCard key={service.id} service={service} result={results[service.id]} loading={loading} />
        ))}
      </div>
      <p className="mt-6 text-xs text-slate-500">
        Gateway URL: <span className="font-mono text-slate-400">{import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080'}</span>
      </p>
    </>
  );
}
