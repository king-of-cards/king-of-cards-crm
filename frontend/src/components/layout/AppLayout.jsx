import { NavLink, Outlet } from 'react-router-dom';

const linkClasses = ({ isActive }) =>
  `rounded-lg px-3 py-2 text-sm font-medium transition ${
    isActive ? 'bg-white/10 text-white' : 'text-slate-400 hover:bg-white/5 hover:text-white'
  }`;

export default function AppLayout() {
  return (
    <div className="min-h-screen bg-[#0c1220] text-slate-100">
      <header className="border-b border-white/10 bg-[#101827]/90">
        <div className="mx-auto flex max-w-7xl flex-col gap-4 px-5 py-4 sm:flex-row sm:items-center sm:justify-between sm:px-8">
          <NavLink to="/" className="flex items-center gap-3" aria-label="King of Cards CRM home">
            <span className="grid size-10 place-items-center rounded-xl bg-amber-300 text-lg font-black text-slate-950">K</span>
            <span>
              <span className="block text-sm font-semibold tracking-wide text-white">KING OF CARDS</span>
              <span className="block text-xs text-slate-400">Cross-Selling CRM · Beta</span>
            </span>
          </NavLink>
          <nav aria-label="Main navigation" className="flex items-center gap-2">
            <NavLink to="/" end className={linkClasses}>Overview</NavLink>
            <NavLink to="/status" className={linkClasses}>Service status</NavLink>
          </nav>
        </div>
      </header>
      <main className="mx-auto max-w-7xl px-5 py-8 sm:px-8 sm:py-12">
        <Outlet />
      </main>
      <footer className="mx-auto max-w-7xl px-5 pb-8 text-xs text-slate-500 sm:px-8">
        Development environment · Synthetic data only
      </footer>
    </div>
  );
}
