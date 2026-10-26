import { Link } from 'react-router-dom';

export default function NotFoundPage() {
  return (
    <section className="rounded-2xl border border-white/10 bg-[#131d2d] px-6 py-16 text-center">
      <p className="text-sm font-semibold uppercase tracking-[0.2em] text-amber-300">404</p>
      <h1 className="mt-3 text-3xl font-semibold text-white">Page not found</h1>
      <p className="mt-3 text-sm text-slate-400">That address is not part of this application.</p>
      <Link to="/" className="mt-6 inline-flex rounded-lg bg-white/10 px-4 py-2.5 text-sm font-semibold text-white hover:bg-white/15">Return to overview</Link>
    </section>
  );
}
