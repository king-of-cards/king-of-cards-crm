export default function ServiceStatusCard({ service, result, loading }) {
  const healthy = result?.ok === true;
  const badgeClasses = loading
    ? 'bg-slate-700/60 text-slate-300'
    : healthy
      ? 'bg-emerald-400/10 text-emerald-300 ring-1 ring-emerald-400/20'
      : 'bg-rose-400/10 text-rose-300 ring-1 ring-rose-400/20';

  return (
    <article className="rounded-2xl border border-white/10 bg-[#131d2d] p-5 shadow-xl shadow-black/10 sm:p-6">
      <div className="flex items-start justify-between gap-4">
        <div>
          <h2 className="font-semibold text-white">{service.label}</h2>
          <p className="mt-2 break-all font-mono text-xs text-slate-500">{service.path}</p>
        </div>
        <span className={`shrink-0 rounded-full px-3 py-1 text-xs font-semibold ${badgeClasses}`}>
          {loading ? 'Checking' : healthy ? 'Online' : 'Unavailable'}
        </span>
      </div>
      <div className="mt-6 border-t border-white/10 pt-4 text-sm text-slate-400">
        {loading
          ? 'Requesting health endpoint through the API Gateway…'
          : healthy
            ? `Response: ${result.data.service} · ${result.data.status}`
            : result?.message || 'No health response received.'}
      </div>
    </article>
  );
}
