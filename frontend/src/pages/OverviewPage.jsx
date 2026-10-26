import { Link } from 'react-router-dom';
import PageHeader from '../components/ui/PageHeader.jsx';

export default function OverviewPage() {
  return (
    <>
      <PageHeader
        eyebrow="Development foundation"
        title="Cross-Selling CRM"
        description="The application shell is ready. Check the backend service connections while the CRM product modules are being planned."
      />
      <section className="grid gap-5 lg:grid-cols-[1.4fr_0.6fr]">
        <div className="rounded-2xl border border-white/10 bg-gradient-to-br from-[#18263a] to-[#131a29] p-6 sm:p-8">
          <p className="text-sm font-medium text-amber-200">Beta workspace</p>
          <h2 className="mt-3 max-w-xl text-2xl font-semibold leading-tight text-white sm:text-3xl">
            A clear starting point for the King of Cards CRM.
          </h2>
          <p className="mt-4 max-w-xl text-sm leading-6 text-slate-400">
            This frontend foundation provides navigation, a responsive layout, and a live connectivity check for the backend APIs. No CRM workflows are enabled here.
          </p>
          <Link to="/status" className="mt-7 inline-flex rounded-lg bg-amber-300 px-4 py-2.5 text-sm font-semibold text-slate-950 transition hover:bg-amber-200">
            Check backend status <span aria-hidden="true" className="ml-2">→</span>
          </Link>
        </div>
        <aside className="rounded-2xl border border-white/10 bg-[#131d2d] p-6">
          <p className="text-xs font-semibold uppercase tracking-[0.18em] text-slate-500">Connection target</p>
          <p className="mt-3 font-mono text-sm text-white">{import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080'}</p>
          <p className="mt-3 text-sm leading-6 text-slate-400">Configure this URL with <code className="rounded bg-white/5 px-1.5 py-0.5 text-amber-200">VITE_API_BASE_URL</code>.</p>
        </aside>
      </section>
    </>
  );
}
