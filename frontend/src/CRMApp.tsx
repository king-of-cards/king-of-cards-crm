import Login from "./Login";
import { useState } from "react";
import "./CRMApp.css";

const sampleLeads = [
  {
    name: "Abhishek N.",
    phone: "+91 90000 59978",
    service: "Photography",
    stage: "Contacted",
    source: "POS customer data",
    vendor: "Darshan",
  },
  {
    name: "Pranav V.",
    phone: "+91 90000 34008",
    service: "Wedding Planning",
    stage: "Interested",
    source: "WhatsApp campaign",
    vendor: "Hemang",
  },
];

function App() {
  const [isLoggedIn, setIsLoggedIn] = useState(false);
  const [activePage, setActivePage] = useState("Dashboard");
  const [search, setSearch] = useState("");
  const [showSignOut, setShowSignOut] = useState(false);
  
  if (!isLoggedIn) {
  return <Login onLogin={() => setIsLoggedIn(true)} />;
}

  const navigation = [
    { name: "Dashboard", icon: "▦" },
    { name: "Vendors", icon: "♙" },
    { name: "Leads", icon: "☷" },
    { name: "Follow-ups", icon: "◷" },
    { name: "Cross-selling", icon: "⇄" },
    { name: "Reports", icon: "▥" },
    { name: "Settings", icon: "⚙" },
  ];

  const leads = sampleLeads.filter((lead) =>
    `${lead.name} ${lead.phone} ${lead.vendor}`
      .toLowerCase()
      .includes(search.toLowerCase())
  );

  const kpis = [
    {
      title: "Total Leads",
      value: "119",
      description: "Unique customers · 2 repeat records merged",
      icon: "♙",
    },
    {
      title: "Interested",
      value: "27",
      description: "Interested or in follow-up",
      icon: "◎",
    },
    {
      title: "Converted",
      value: "15",
      description: "Confirmed bookings, orders, disbursals",
      icon: "◉",
    },
    {
      title: "Conv. Rate",
      value: "13.3%",
      description: "15 of 113 leads shared",
      icon: "↗",
    },
    {
      title: "Revenue / Commission",
      value: "₹22,19,000",
      description: "Commission ₹74,400 · 6 not entered",
      icon: "₹",
    },
  ];

 function handleSignOut() {
  setShowSignOut(false);
  setIsLoggedIn(false);
}

  return (
    <div className="crm">
      <aside className="sidebar">
        <a
          className="brand"
          href="#dashboard"
          onClick={() => setActivePage("Dashboard")}
        >
         <img
              className="brand-logo"
              src="/Koc_logo.png"
              alt="King of Cards logo"
/>
          <span>
            <strong>King of Cards</strong>
            <small>CROSS-SELLING CRM</small>
          </span>
        </a>

        <div className="nav-label">WORKSPACE</div>

        <nav className="navigation">
          {navigation.map((item) => (
            <button
              key={item.name}
              className={`nav-item ${
                activePage === item.name ? "active" : ""
              }`}
              onClick={() => setActivePage(item.name)}
            >
              <span className="nav-icon">{item.icon}</span>
              <span>{item.name}</span>
              {item.name === "Leads" && (
                <span className="nav-count">18</span>
              )}
            </button>
          ))}
        </nav>

        <div className="sidebar-bottom">
          <div className="user-profile">
            <div className="avatar">A</div>
            <div className="user-details">
              <strong>CRM Admin</strong>
            </div>
          </div>

          <button
            className="signout-button"
            onClick={() => setShowSignOut(true)}
          >
            <span>⇥</span>
            Sign Out
          </button>
        </div>
      </aside>

      <main className="main-content">
        <header className="topbar">
          <div>
            <div className="breadcrumb">WORKSPACE / {activePage.toUpperCase()}</div>
            <h1>{activePage}</h1>
            <p className="page-subtitle">
              {activePage === "Dashboard"
                ? "Welcome back. Here's your cross-selling overview."
                : `Manage your ${activePage.toLowerCase()} activities.`}
            </p>
          </div>

          <div className="topbar-actions">
            <button
              className="notification-button"
              aria-label="Notifications"
              onClick={() => alert("Notifications will be available soon.")}
            >
              ♧ <span className="notification-dot" />
            </button>
            <div className="topbar-user">
              <div className="avatar small">A</div>
              <div>
                <strong>Administrator</strong>
                <span>King of Cards</span>
              </div>
            </div>
          </div>
        </header>

        {activePage === "Dashboard" ? (
          <>

            <section className="section-block">
              <div className="section-heading">
                <div>
                  <h2>Performance Overview</h2>
                  <p>Your key cross-selling metrics at a glance.</p>
                </div>

                <label className="filter-control">
                  <span>Vendor</span>
                  <select aria-label="Filter KPI by vendor">
                    <option>All vendors</option>
                    <option>Darshan</option>
                    <option>Hemang</option>
                  </select>
                </label>
              </div>

              <div className="kpi-grid">
                {kpis.map((kpi) => (
                  <article className="kpi-card" key={kpi.title}>
                    <div className="kpi-top">
                      <span>{kpi.title}</span>
                      <span className="kpi-icon">{kpi.icon}</span>
                    </div>
                    <strong className="kpi-value">{kpi.value}</strong>
                    <p>{kpi.description}</p>
                  </article>
                ))}
              </div>
              <p className="sample-note">
                Sample figures for the prototype. Live metrics will come from
                the backend.
              </p>
            </section>

            <section className="panel leads-panel" id="leads">
              <div className="panel-heading">
                <div>
                  <div className="heading-with-count">
                    <h2>Leads</h2>
                    <span className="count-badge">18 new leads</span>
                  </div>
                  <p>Review customer enquiries and their current stages.</p>
                </div>

                <div className="panel-actions">
                  <button
                    className="button-secondary"
                    onClick={() => alert("CSV import will be implemented next.")}
                  >
                    ↑ Import CSV
                  </button>
                  <button
                    className="button-primary"
                    onClick={() => alert("The Add Lead form is coming next.")}
                  >
                    + Add Lead
                  </button>
                </div>
              </div>

              <div className="lead-toolbar">
                <select aria-label="Filter by service" defaultValue="all">
                  <option value="all">All services</option>
                  <option>Photography</option>
                  <option>Wedding Planning</option>
                </select>

                <select aria-label="Filter by stage" defaultValue="all">
                  <option value="all">All stages</option>
                  <option>Contacted</option>
                  <option>Interested</option>
                  <option>Converted</option>
                </select>

                <input
                  type="search"
                  placeholder="Search name, phone, vendor..."
                  aria-label="Search leads"
                  value={search}
                  onChange={(event) => setSearch(event.target.value)}
                />
              </div>

              <div className="table-wrap">
                <table>
                  <thead>
                    <tr>
                      <th>Name</th>
                      <th>Phone</th>
                      <th>Service</th>
                      <th>Stage</th>
                      <th>Lead Source</th>
                      <th>Vendor</th>
                    </tr>
                  </thead>
                  <tbody>
                    {leads.map((lead) => (
                      <tr key={lead.phone}>
                        <td>
                          <strong>{lead.name}</strong>
                        </td>
                        <td>{lead.phone}</td>
                        <td>{lead.service}</td>
                        <td>
                          <span
                            className={`stage-badge ${lead.stage.toLowerCase()}`}
                          >
                            <span /> {lead.stage}
                          </span>
                        </td>
                        <td>{lead.source}</td>
                        <td>{lead.vendor}</td>
                      </tr>
                    ))}
                    {leads.length === 0 && (
                      <tr>
                        <td colSpan={6} className="empty-state">
                          No matching leads found.
                        </td>
                      </tr>
                    )}
                  </tbody>
                </table>
              </div>

              <div className="table-footer">
                Showing {leads.length} sample records
                <span>Live lead data will be connected later.</span>
              </div>
            </section>

            <section className="bottom-grid">
              <article className="panel">
                <div className="panel-heading">
                  <div>
                    <h2>Quick Actions</h2>
                    <p>Shortcuts to your everyday work.</p>
                  </div>
                </div>
                <div className="quick-actions">
                  <button
                    onClick={() => setActivePage("Vendors")}
                    className="quick-action"
                  >
                    <span className="action-icon">♙</span>
                    <span><strong>Add Vendor</strong><small>Manage vendor partners</small></span>
                    <span className="action-arrow">→</span>
                  </button>
                  <button
                    onClick={() => setActivePage("Leads")}
                    className="quick-action"
                  >
                    <span className="action-icon">☷</span>
                    <span><strong>Manage Leads</strong><small>Track customer enquiries</small></span>
                    <span className="action-arrow">→</span>
                  </button>
                  <button
                    onClick={() => setActivePage("Follow-ups")}
                    className="quick-action"
                  >
                    <span className="action-icon">◷</span>
                    <span><strong>Schedule Follow-up</strong><small>Plan your next interaction</small></span>
                    <span className="action-arrow">→</span>
                  </button>
                </div>
              </article>

              <article className="panel activity-panel">
                <div className="panel-heading">
                  <div>
                    <h2>Recent Activity</h2>
                    <p>Keep track of your latest work.</p>
                  </div>
                </div>
                <div className="activity-empty">
                  <span className="activity-symbol">◷</span>
                  <strong>No recent activity</strong>
                  <p>Activity will appear here as you start using the CRM.</p>
                </div>
              </article>
            </section>
          </>
        ) : (
          <section className="panel placeholder-panel">
            <span className="placeholder-icon">♧</span>
            <h2>{activePage}</h2>
            <p>
              This section is part of the CRM navigation. We'll build its
              working features step by step.
            </p>
            <button
              className="button-primary"
              onClick={() => setActivePage("Dashboard")}
            >
              Back to Dashboard
            </button>
          </section>
        )}

        <footer className="footer">
          <span>King of Cards · Cross-Selling CRM</span>
          <span>Prototype · Sample data</span>
        </footer>
      </main>

      {showSignOut && (
        <div
          className="modal-backdrop"
          onClick={() => setShowSignOut(false)}
        >
          <section
            className="confirm-modal"
            role="dialog"
            aria-modal="true"
            aria-labelledby="signout-title"
            onClick={(event) => event.stopPropagation()}
          >
            <h2 id="signout-title">Sign out?</h2>
            <p>
              Are you sure you want to sign out of King of Cards CRM?
            </p>
            <div className="modal-actions">
              <button
                className="button-secondary"
                onClick={() => setShowSignOut(false)}
              >
                Cancel
              </button>
              <button className="button-danger" onClick={handleSignOut}>
                Sign Out
              </button>
            </div>
          </section>
        </div>
      )}
    </div>
  );
}

export default App;
