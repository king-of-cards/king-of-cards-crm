
import Login, { type UserRole } from "./Login";
import { useEffect, useState, type FormEvent } from "react";
import "./CRMApp.css";

type Service =
  | "Photography"
  | "Videography"
  | "Wedding Planning"
  | "Make-up Artist";

type LeadStage = "Called" | "Interested" | "Converted" | "Not Interested" | "WhatsApp";

type LeadNote = {
  id: number;
  text: string;
  addedBy: string;
  createdAt: string;
  editedAt?: string;
};

type Lead = {
  id: number;
  name: string;
  email: string;
  phone: string;
  service: Service;
  stage: LeadStage;
  source: string;
  vendor: string;
  assignedDate: string;
  followUp: string;
  followUpDate: string;
  followUpTime: string;
  notes: LeadNote[];
};

/*
 * NOTE: the file you sent did not contain the Vendor type or the
 * sampleVendors list (the code uses them), so they are rebuilt here from
 * your vendorAccounts. If your own project already has a Vendor type and a
 * sampleVendors list with real phone/email/location, KEEP YOUR OWN and
 * delete this block.
 */
type Vendor = {
  id: number;
  name: string;
  service: Service;
  location: string;
  phone: string;
  email: string;
  status: "Active" | "Inactive";
};

const services: Service[] = [
  "Photography",
  "Videography",
  "Wedding Planning",
  "Make-up Artist",
];

const sampleVendors: Vendor[] = [
  { id: 1, name: "Phodeo", service: "Photography", location: "Bengaluru", phone: "+91 90000 10001", email: "contact@phodeo.example.com", status: "Active" },
  { id: 2, name: "RJ Photography", service: "Photography", location: "Bengaluru", phone: "+91 90000 10002", email: "contact@rjphotography.example.com", status: "Active" },
  { id: 3, name: "One Horizon", service: "Photography", location: "Bengaluru", phone: "+91 90000 10003", email: "contact@onehorizon.example.com", status: "Active" },
  { id: 4, name: "Frame Stories", service: "Videography", location: "Bengaluru", phone: "+91 90000 10004", email: "contact@framestories.example.com", status: "Active" },
  { id: 5, name: "Pixel Films", service: "Videography", location: "Bengaluru", phone: "+91 90000 10005", email: "contact@pixelfilms.example.com", status: "Active" },
  { id: 6, name: "Motion House", service: "Videography", location: "Bengaluru", phone: "+91 90000 10006", email: "contact@motionhouse.example.com", status: "Active" },
  { id: 7, name: "Hemang", service: "Wedding Planning", location: "Bengaluru", phone: "+91 90000 10007", email: "contact@hemang.example.com", status: "Active" },
  { id: 8, name: "Dream Weddings", service: "Wedding Planning", location: "Bengaluru", phone: "+91 90000 10008", email: "contact@dreamweddings.example.com", status: "Active" },
  { id: 9, name: "The Wedding Co.", service: "Wedding Planning", location: "Bengaluru", phone: "+91 90000 10009", email: "contact@theweddingco.example.com", status: "Active" },
  { id: 10, name: "Glam Studio", service: "Make-up Artist", location: "Bengaluru", phone: "+91 90000 10010", email: "contact@glamstudio.example.com", status: "Active" },
  { id: 11, name: "Makeup by Riya", service: "Make-up Artist", location: "Bengaluru", phone: "+91 90000 10011", email: "contact@makeupbyriya.example.com", status: "Active" },
  { id: 12, name: "Blush & Glow", service: "Make-up Artist", location: "Bengaluru", phone: "+91 90000 10012", email: "contact@blushandglow.example.com", status: "Active" },
];

const sampleLeads: Lead[] = [
  {
    id: 1, name: "Abhishek N.", email: "abhishek@example.com",
    phone: "+91 90000 59978", service: "Photography",
    stage: "Called", source: "POS customer data", vendor: "Phodeo",
    assignedDate: "20 Oct 2026", followUp: "30 Oct 2026",
    followUpDate: "2026-10-30", followUpTime: "11:00", notes: [],
  },
  {
    id: 2, name: "Pranav V.", email: "pranav@example.com",
    phone: "+91 90000 34008", service: "Wedding Planning",
    stage: "Interested", source: "WhatsApp campaign", vendor: "Hemang",
    assignedDate: "21 Oct 2026", followUp: "31 Oct 2026",
    followUpDate: "2026-10-31", followUpTime: "12:00", notes: [],
  },
  {
    id: 3, name: "Sneha R.", email: "sneha@example.com",
    phone: "+91 90000 34009", service: "Photography",
    stage: "Converted", source: "POS customer data", vendor: "RJ Photography",
    assignedDate: "18 Oct 2026", followUp: "Completed",
    followUpDate: "", followUpTime: "", notes: [],
  },
  {
    id: 4, name: "Rahul M.", email: "rahul@example.com",
    phone: "+91 90000 34010", service: "Videography",
    stage: "Interested", source: "Website enquiry", vendor: "Frame Stories",
    assignedDate: "22 Oct 2026", followUp: "01 Nov 2026",
    followUpDate: "2026-11-01", followUpTime: "10:30", notes: [],
  },
  {
    id: 5, name: "Priya S.", email: "priya@example.com",
    phone: "+91 90000 34011", service: "Make-up Artist",
    stage: "Called", source: "WhatsApp campaign", vendor: "Glam Studio",
    assignedDate: "23 Oct 2026", followUp: "02 Nov 2026",
    followUpDate: "2026-11-02", followUpTime: "14:00", notes: [],
  },
  {
    id: 6, name: "Amit K.", email: "amit@example.com",
    phone: "+91 90000 34012", service: "Photography",
    stage: "Interested", source: "POS customer data", vendor: "One Horizon",
    assignedDate: "24 Oct 2026", followUp: "03 Nov 2026",
    followUpDate: "2026-11-03", followUpTime: "11:30", notes: [],
  },
  {
    id: 7, name: "Neha P.", email: "neha@example.com",
    phone: "+91 90000 34013", service: "Wedding Planning",
    stage: "Called", source: "Website enquiry", vendor: "Dream Weddings",
    assignedDate: "24 Oct 2026", followUp: "04 Nov 2026",
    followUpDate: "2026-11-04", followUpTime: "15:00", notes: [],
  },
  {
    id: 8, name: "Kavya D.", email: "kavya@example.com",
    phone: "+91 90000 34014", service: "Videography",
    stage: "Converted", source: "POS customer data", vendor: "Pixel Films",
    assignedDate: "19 Oct 2026", followUp: "Completed",
    followUpDate: "", followUpTime: "", notes: [],
  },
  {
    id: 9, name: "Rohan B.", email: "rohan@example.com",
    phone: "+91 90000 34015", service: "Photography",
    stage: "Called", source: "Website enquiry", vendor: "Phodeo",
    assignedDate: "25 Oct 2026", followUp: "05 Nov 2026",
    followUpDate: "2026-11-05", followUpTime: "10:00", notes: [],
  },
  {
    id: 10, name: "Meera T.", email: "meera@example.com",
    phone: "+91 90000 34016", service: "Make-up Artist",
    stage: "Interested", source: "POS customer data", vendor: "Makeup by Riya",
    assignedDate: "25 Oct 2026", followUp: "05 Nov 2026",
    followUpDate: "2026-11-05", followUpTime: "16:00", notes: [],
  },
];

type VendorAccount = {
  username: string;
  password: string;
  vendorName: string;
};

/*
 * DEMO ACCOUNTS ONLY.
 * Replace this with backend authentication when your Java API is ready.
 */
const initialVendorAccounts: VendorAccount[] = [
  { username: "phodeo", password: "123456", vendorName: "Phodeo" },
  { username: "rjphotography", password: "123456", vendorName: "RJ Photography" },
  { username: "onehorizon", password: "123456", vendorName: "One Horizon" },
  { username: "framestories", password: "123456", vendorName: "Frame Stories" },
  { username: "pixelfilms", password: "123456", vendorName: "Pixel Films" },
  { username: "motionhouse", password: "123456", vendorName: "Motion House" },
  { username: "hemang", password: "123456", vendorName: "Hemang" },
  { username: "dreamweddings", password: "123456", vendorName: "Dream Weddings" },
  { username: "theweddingco", password: "123456", vendorName: "The Wedding Co." },
  { username: "glamstudio", password: "123456", vendorName: "Glam Studio" },
  { username: "makeupbyriya", password: "123456", vendorName: "Makeup by Riya" },
  { username: "blushandglow", password: "123456", vendorName: "Blush & Glow" },
];

/*
 * DEMO ADMIN ACCOUNT ONLY.
 * Change these, and replace this check with the Java backend login later.
 */
const adminAccount = { username: "admin", password: "admin123" };

// Saves a value in the browser (localStorage) so it survives a page refresh.
// Temporary: this goes away once the Java backend and database are connected.
function usePersistentState<T>(key: string, initialValue: T) {
  const [value, setValue] = useState<T>(() => {
    try {
      const saved = localStorage.getItem(key);
      return saved ? (JSON.parse(saved) as T) : initialValue;
    } catch {
      return initialValue;
    }
  });

  useEffect(() => {
    try {
      localStorage.setItem(key, JSON.stringify(value));
    } catch {
      // Storage is full or blocked. The app still works without it.
    }
  }, [key, value]);

  return [value, setValue] as const;
}

// Remembers who is signed in while this browser tab is open,
// so a refresh keeps you on your own dashboard.
const SESSION_KEY = "koc-crm-session";

type Session = { role: UserRole; vendorName: string };

function loadSession(): Session | null {
  try {
    const saved = sessionStorage.getItem(SESSION_KEY);
    if (!saved) return null;

    const parsed = JSON.parse(saved) as Session;
    return parsed.role === "Admin" || parsed.role === "Vendor" ? parsed : null;
  } catch {
    return null;
  }
}

// Fields of the "Add Vendor" form.
type VendorForm = {
  name: string;
  service: Service;
  location: string;
  phone: string;
  email: string;
  username: string;
  password: string;
};

const emptyVendorForm: VendorForm = {
  name: "",
  service: "Photography",
  location: "Bengaluru",
  phone: "",
  email: "",
  username: "",
  password: "",
};

// "2026-10-30" -> "30 Oct 2026"
function normalizeLeadStage(value: string): LeadStage {
  const normalized = value.trim().toLowerCase().replace(/\s+/g, " ");

  switch (normalized) {
    case "contacted":
    case "called":
      return "Called";
    case "interested":
      return "Interested";
    case "converted":
      return "Converted";
    case "notinterested":
    case "not interested":
      return "Not Interested";
    case "whatsapp":
      return "WhatsApp";
    default:
      return "Called";
  }
}

function formatFollowUpDate(date: string) {
  if (!date) return "Not scheduled";
  return new Date(`${date}T00:00:00`).toLocaleDateString("en-IN", {
    day: "2-digit",
    month: "short",
    year: "numeric",
  });
}

// "14:00" -> "2:00 pm"
function formatFollowUpTime(time: string) {
  if (!time) return "";
  const [hours, minutes] = time.split(":").map(Number);
  const value = new Date();
  value.setHours(hours, minutes, 0, 0);
  return value.toLocaleTimeString("en-IN", {
    hour: "numeric",
    minute: "2-digit",
    hour12: true,
  });
}

// ISO timestamp -> "31 Oct 2026, 10:42 am"
function formatNoteDateTime(iso: string) {
  return new Date(iso).toLocaleString("en-IN", {
    dateStyle: "medium",
    timeStyle: "short",
  });
}

function App() {
  const [isLoggedIn, setIsLoggedIn] = useState(() => loadSession() !== null);
  const [userRole, setUserRole] = useState<UserRole>(
    () => loadSession()?.role ?? "Admin"
  );
  const [currentVendorName, setCurrentVendorName] = useState(
    () => loadSession()?.vendorName ?? ""
  );
  const [activePage, setActivePage] = useState("Home");
  const [search, setSearch] = useState("");
  const [showSignOut, setShowSignOut] = useState(false);

  const [selectedVendor, setSelectedVendor] = useState<Vendor | null>(null);
  const [vendorSearch, setVendorSearch] = useState("");
  const [selectedService, setSelectedService] = useState<Service | "All">("All");
  const [stageFilter, setStageFilter] = useState("All");
  const [leadSearch, setLeadSearch] = useState("");
  const [dashboardVendor, setDashboardVendor] = useState("All");

  // Leads, vendors and vendor logins are kept in React state (and saved in the
  // browser for now) so admin edits update every screen.
  const [leads, setLeads] = usePersistentState<Lead[]>(
    "koc-crm-leads-v1",
    sampleLeads
  );
  // Migrate old status spellings already saved in this browser.
  useEffect(() => {
    let needsMigration = false;
    const normalizedLeads = leads.map((lead) => {
      const stage = normalizeLeadStage(String(lead.stage));
      if (stage !== lead.stage) needsMigration = true;
      return stage === lead.stage ? lead : { ...lead, stage };
    });

    if (needsMigration) setLeads(normalizedLeads);
  }, [leads, setLeads]);

  const [vendors, setVendors] = usePersistentState<Vendor[]>(
    "koc-crm-vendors-v1",
    sampleVendors
  );
  const [vendorAccounts, setVendorAccounts] = usePersistentState<
    VendorAccount[]
  >("koc-crm-vendor-accounts-v1", initialVendorAccounts);

  // "Add Vendor" form (admin only).
  const [showAddVendor, setShowAddVendor] = useState(false);
  const [vendorForm, setVendorForm] = useState<VendorForm>(emptyVendorForm);
  const [vendorFormError, setVendorFormError] = useState("");

  // Lead Details drawer state.
  const [selectedLeadId, setSelectedLeadId] = useState<number | null>(null);
  const [newNote, setNewNote] = useState("");
  const [showAllNotes, setShowAllNotes] = useState(false);

  // Admin note-editing state.
  const [editingNoteId, setEditingNoteId] = useState<number | null>(null);
  const [editingNoteText, setEditingNoteText] = useState("");

  const selectedLead = leads.find((lead) => lead.id === selectedLeadId) ?? null;

  // Press Esc to close the Lead Details drawer.
  useEffect(() => {
    if (selectedLeadId === null) return;

    function handleKeyDown(event: KeyboardEvent) {
      if (event.key === "Escape") {
        setSelectedLeadId(null);
        setNewNote("");
        setShowAllNotes(false);
        setEditingNoteId(null);
        setEditingNoteText("");
      }
    }

    window.addEventListener("keydown", handleKeyDown);
    return () => window.removeEventListener("keydown", handleKeyDown);
  }, [selectedLeadId]);

  // Remember the signed-in user for this browser tab.
  useEffect(() => {
    try {
      if (isLoggedIn) {
        sessionStorage.setItem(
          SESSION_KEY,
          JSON.stringify({ role: userRole, vendorName: currentVendorName })
        );
      } else {
        sessionStorage.removeItem(SESSION_KEY);
      }
    } catch {
      // Storage blocked. Signing in still works, a refresh will sign out.
    }
  }, [isLoggedIn, userRole, currentVendorName]);

  // Automatic routing: the web address always matches who is signed in.
  //   signed out  -> /        (the one login page)
  //   admin       -> /admin
  //   vendor      -> /vendor
  useEffect(() => {
    function syncRoute() {
      const expected = !isLoggedIn
        ? "/"
        : userRole === "Admin"
          ? "/admin"
          : "/vendor";

      if (window.location.pathname !== expected) {
        window.history.replaceState(null, "", expected);
      }
    }

    syncRoute();
    window.addEventListener("popstate", syncRoute);
    return () => window.removeEventListener("popstate", syncRoute);
  }, [isLoggedIn, userRole]);

  // One login page for everyone. The username and password decide the
  // dashboard: admin details -> Admin dashboard, vendor details -> that
  // vendor's own dashboard.
  if (!isLoggedIn) {
    return (
      <Login
        mode="Any"
        onLogin={(_mode, username, password) => {
          const isAdmin =
            username.toLowerCase() === adminAccount.username &&
            password === adminAccount.password;

          if (isAdmin) {
            setUserRole("Admin");
            setCurrentVendorName("");
            setActivePage("Home");
            setSelectedVendor(null);
            setIsLoggedIn(true);
            return;
          }

          const account = vendorAccounts.find(
            (vendor) =>
              vendor.username.toLowerCase() === username.toLowerCase() &&
              vendor.password === password
          );

          if (!account) {
            alert("Invalid username or password");
            return;
          }

          setUserRole("Vendor");
          setCurrentVendorName(account.vendorName);
          setLeadSearch("");
          setStageFilter("All");
          setActivePage("Home");
          setSelectedVendor(null);
          setIsLoggedIn(true);
        }}
      />
    );
  }

  const navigation = [
    { name: "Home", icon: "▦" },
    { name: "Vendors", icon: "♙" },
    { name: "Leads", icon: "☷" },
    { name: "Follow-ups", icon: "◷" },
    { name: "Reports", icon: "▥" },
  ];

  const filteredVendors = vendors.filter((vendor) => {
    const matchesSearch =
      `${vendor.name} ${vendor.location} ${vendor.service}`
        .toLowerCase()
        .includes(vendorSearch.toLowerCase());

    const matchesService =
      selectedService === "All" || vendor.service === selectedService;

    return matchesSearch && matchesService;
  });

  const dashboardLeads = leads.filter((lead) => {
    const matchesSearch =
      `${lead.name} ${lead.phone} ${lead.vendor} ${lead.service}`
        .toLowerCase()
        .includes(search.toLowerCase());

    const matchesVendor =
      dashboardVendor === "All" || lead.vendor === dashboardVendor;

    return matchesSearch && matchesVendor;
  });

  const vendorLeads = selectedVendor
    ? leads.filter((lead) => lead.vendor === selectedVendor.name)
    : [];

  const loggedInVendor = vendors.find(
    (vendor) => vendor.name === currentVendorName
  );

  // This list is restricted to the logged-in vendor.
  const loggedInVendorLeads = leads.filter(
    (lead) => lead.vendor === currentVendorName
  );

  const filteredLoggedInVendorLeads = loggedInVendorLeads.filter((lead) => {
    const matchesSearch =
      `${lead.name} ${lead.phone} ${lead.source}`
        .toLowerCase()
        .includes(leadSearch.toLowerCase());

    const matchesStage =
      stageFilter === "All" || lead.stage === stageFilter;

    return matchesSearch && matchesStage;
  });

  const vendorDashboardCounts = {
    total: loggedInVendorLeads.length,
    contacted: loggedInVendorLeads.filter(
      (lead) => lead.stage === "Called"
    ).length,
    interested: loggedInVendorLeads.filter(
      (lead) => lead.stage === "Interested"
    ).length,
    converted: loggedInVendorLeads.filter(
      (lead) => lead.stage === "Converted"
    ).length,
    notInterested: loggedInVendorLeads.filter(
      (lead) => lead.stage === "Not Interested"
    ).length,
    whatsapp: loggedInVendorLeads.filter(
      (lead) => lead.stage === "WhatsApp"
    ).length,
  };

  const filteredVendorLeads = vendorLeads.filter((lead) => {
    const matchesSearch =
      `${lead.name} ${lead.phone} ${lead.source}`
        .toLowerCase()
        .includes(leadSearch.toLowerCase());

    const matchesStage =
      stageFilter === "All" || lead.stage === stageFilter;

    return matchesSearch && matchesStage;
  });

  const kpis = [
    { title: "Total Leads", value: "119", description: "Unique customers · repeat records merged", icon: "♙" },
    { title: "Interested", value: "27", description: "Interested or in follow-up", icon: "◎" },
    { title: "Converted", value: "15", description: "Confirmed bookings and orders", icon: "◉" },
    { title: "Conv. Rate", value: "13.3%", description: "15 of 113 leads shared", icon: "↗" },
    { title: "Revenue / Commission", value: "₹22,19,000", description: "Commission ₹74,400", icon: "₹" },
  ];

  const leadCounts = {
    total: vendorLeads.length,
    called:vendorLeads.filter((lead) => lead.stage === "Called").length,
    interested: vendorLeads.filter((lead) => lead.stage === "Interested").length,
    converted: vendorLeads.filter((lead) => lead.stage === "Converted").length,
    notInterested: vendorLeads.filter((lead) => lead.stage === "Not Interested").length,
    whatsapp: vendorLeads.filter((lead) => lead.stage === "WhatsApp").length,

  };

  function openVendor(vendor: Vendor) {
    setSelectedVendor(vendor);
    setActivePage("Vendors");
    setLeadSearch("");
    setStageFilter("All");
  }

  /* ---------- Lead Details drawer: open / close ---------- */

  function openLead(lead: Lead) {
    setSelectedLeadId(lead.id);
    setNewNote("");
    setShowAllNotes(false);
    setEditingNoteId(null);
    setEditingNoteText("");
  }

  function closeLeadDrawer() {
    setSelectedLeadId(null);
    setNewNote("");
    setShowAllNotes(false);
    setEditingNoteId(null);
    setEditingNoteText("");
  }

  function handleSignOut() {
    closeLeadDrawer();
    setShowSignOut(false);
    setIsLoggedIn(false);
    setCurrentVendorName("");
    setSelectedVendor(null);
    setActivePage("Home");
    setLeadSearch("");
    setStageFilter("All");
    setSearch("");
    setVendorSearch("");
    setSelectedService("All");
    setDashboardVendor("All");
  }

  /* ---------- Admin: add and remove vendors ---------- */

  function openAddVendor() {
    if (userRole !== "Admin") return;

    setVendorForm(emptyVendorForm);
    setVendorFormError("");
    setShowAddVendor(true);
  }

  function closeAddVendor() {
    setShowAddVendor(false);
    setVendorForm(emptyVendorForm);
    setVendorFormError("");
  }

  function updateVendorForm(patch: Partial<VendorForm>) {
    setVendorForm((current) => ({ ...current, ...patch }));
    setVendorFormError("");
  }

  function addVendor(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (userRole !== "Admin") return;

    const name = vendorForm.name.trim();
    const username = vendorForm.username.trim().toLowerCase();
    const password = vendorForm.password;

    if (!name) {
      setVendorFormError("Please enter the vendor name.");
      return;
    }

    if (vendors.some((vendor) => vendor.name.toLowerCase() === name.toLowerCase())) {
      setVendorFormError("A vendor with this name already exists.");
      return;
    }

    if (!username || /\s/.test(username)) {
      setVendorFormError("Enter a login username without spaces.");
      return;
    }

    if (
      username === adminAccount.username ||
      vendorAccounts.some((account) => account.username.toLowerCase() === username)
    ) {
      setVendorFormError("This username is already taken. Choose another one.");
      return;
    }

    if (password.length < 6) {
      setVendorFormError("The password must be at least 6 characters.");
      return;
    }

    const nextId = vendors.reduce((highest, vendor) => Math.max(highest, vendor.id), 0) + 1;

    setVendors((current) => [
      ...current,
      {
        id: nextId,
        name,
        service: vendorForm.service,
        location: vendorForm.location.trim() || "Not provided",
        phone: vendorForm.phone.trim(),
        email: vendorForm.email.trim(),
        status: "Active",
      },
    ]);

    setVendorAccounts((current) => [
      ...current,
      { username, password, vendorName: name },
    ]);

    closeAddVendor();
  }

  function removeVendor(vendor: Vendor) {
    if (userRole !== "Admin") return;

    const assignedCount = leads.filter((lead) => lead.vendor === vendor.name).length;

    const message =
      `Remove ${vendor.name}?\n\nTheir login will stop working immediately.` +
      (assignedCount > 0
        ? `\n${assignedCount} lead${assignedCount === 1 ? "" : "s"} assigned to this vendor will be marked "Unassigned".`
        : "");

    if (!window.confirm(message)) return;

    setVendors((current) => current.filter((item) => item.id !== vendor.id));
    setVendorAccounts((current) =>
      current.filter((account) => account.vendorName !== vendor.name)
    );
    setLeads((current) =>
      current.map((lead) =>
        lead.vendor === vendor.name ? { ...lead, vendor: "Unassigned" } : lead
      )
    );

    if (dashboardVendor === vendor.name) setDashboardVendor("All");
    setSelectedVendor(null);
  }

  /* ---------- Lead Details drawer: role-based permissions ---------- */

  // Admin can edit every lead. A vendor can edit only leads assigned to
  // the vendor account that is currently signed in.
  function canCurrentUserEditLead(lead: Lead) {
    return (
      userRole === "Admin" ||
      (userRole === "Vendor" && lead.vendor === currentVendorName)
    );
  }

  // Vendors can edit/delete only interaction notes they personally added.
  // Admin can manage every note on every lead.
  function canCurrentUserEditNote(note: LeadNote) {
    if (userRole === "Admin") return true;

    return (
      userRole === "Vendor" &&
      selectedLead !== null &&
      canCurrentUserEditLead(selectedLead) &&
      note.addedBy === currentVendorName
    );
  }

  function updateSelectedLead(patch: Partial<Lead>) {
    if (!selectedLead || !canCurrentUserEditLead(selectedLead)) return;

    setLeads((currentLeads) =>
      currentLeads.map((lead) =>
        lead.id === selectedLead.id ? { ...lead, ...patch } : lead
      )
    );
  }

  function changeLeadStage(stage: LeadStage) {
    if (!selectedLead || !canCurrentUserEditLead(selectedLead)) return;

    updateSelectedLead({
      stage,
      followUp:
        stage === "Called"
          ? "Completed"
          : formatFollowUpDate(selectedLead.followUpDate),
    });
  }

  // Follow-up scheduling remains Admin-only.
  function changeFollowUpDate(date: string) {
    if (userRole !== "Admin" || !selectedLead) return;

    updateSelectedLead({
      followUpDate: date,
      followUp:
        selectedLead.stage === "Called"
          ? "Completed"
          : formatFollowUpDate(date),
    });
  }

  function changeFollowUpTime(time: string) {
    if (userRole !== "Admin" || !selectedLead) return;
    updateSelectedLead({ followUpTime: time });
  }

  function addLeadNote() {
    if (
      !selectedLead ||
      !canCurrentUserEditLead(selectedLead) ||
      !newNote.trim()
    ) {
      return;
    }

    const note: LeadNote = {
      id: Date.now(),
      text: newNote.trim(),
      addedBy: userRole === "Admin" ? "CRM Admin" : currentVendorName,
      createdAt: new Date().toISOString(),
    };

    updateSelectedLead({
      notes: [...selectedLead.notes, note],
    });

    setNewNote("");
  }

  function startEditingNote(note: LeadNote) {
    if (!canCurrentUserEditNote(note)) return;

    setEditingNoteId(note.id);
    setEditingNoteText(note.text);
  }

  function saveEditedNote() {
    if (!selectedLead || editingNoteId === null || !editingNoteText.trim()) {
      return;
    }

    const noteBeingEdited = selectedLead.notes.find(
      (note) => note.id === editingNoteId
    );
    if (!noteBeingEdited || !canCurrentUserEditNote(noteBeingEdited)) return;

    updateSelectedLead({
      notes: selectedLead.notes.map((note) =>
        note.id === editingNoteId
          ? {
              ...note,
              text: editingNoteText.trim(),
              editedAt: new Date().toISOString(),
            }
          : note
      ),
    });

    setEditingNoteId(null);
    setEditingNoteText("");
  }

  function deleteLeadNote(noteId: number) {
    if (!selectedLead) return;

    const noteToDelete = selectedLead.notes.find(
      (note) => note.id === noteId
    );
    if (!noteToDelete || !canCurrentUserEditNote(noteToDelete)) return;

    const confirmed = window.confirm("Delete this interaction note?");
    if (!confirmed) return;

    updateSelectedLead({
      notes: selectedLead.notes.filter((note) => note.id !== noteId),
    });

    if (editingNoteId === noteId) {
      setEditingNoteId(null);
      setEditingNoteText("");
    }
  }

  /* ---------- Lead Details drawer: the panel itself ---------- */

  function renderLeadDrawer() {
    if (!selectedLead) return null;

    const isAdmin = userRole === "Admin";
    const canEditLead = canCurrentUserEditLead(selectedLead);

    // Newest note first.
    const sortedNotes = [...selectedLead.notes].sort(
      (a, b) =>
        new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime()
    );

    // Show the latest 3 notes, "Show More" reveals the rest.
    const visibleNotes = showAllNotes ? sortedNotes : sortedNotes.slice(0, 3);

    const followUpSummary = selectedLead.followUpDate
      ? `${formatFollowUpDate(selectedLead.followUpDate)}${
          selectedLead.followUpTime
            ? ` · ${formatFollowUpTime(selectedLead.followUpTime)}`
            : ""
        }`
      : "Not scheduled";

    return (
      <div className="lead-drawer-backdrop" onClick={closeLeadDrawer}>
        <aside
          className="lead-drawer"
          role="dialog"
          aria-modal="true"
          aria-labelledby="lead-drawer-title"
          onClick={(event) => event.stopPropagation()}
        >
          <header className="lead-drawer-header">
            <div>
              <span className="eyebrow">CUSTOMER LEAD #{selectedLead.id}</span>
              <h2 id="lead-drawer-title">{selectedLead.name}</h2>
              <p>
                {selectedLead.service}
                {!isAdmin && <span className="Editable-badge">Editable</span>}
              </p>
            </div>

            <button
              type="button"
              className="lead-drawer-close"
              onClick={closeLeadDrawer}
              aria-label="Close lead details"
            >
              ×
            </button>
          </header>

          <div className="lead-drawer-content">
            {/* Customer information */}
            <section className="lead-detail-section">
              <h3>Customer Details</h3>

              <div className="lead-detail-grid">
                <div>
                  <small>Customer Name</small>
                  <strong>{selectedLead.name}</strong>
                </div>

                <div>
                  <small>Phone Number</small>
                  <strong>{selectedLead.phone}</strong>
                </div>

                <div>
                  <small>Email Address</small>
                  <strong>{selectedLead.email}</strong>
                </div>

                <div>
                  <small>Service</small>
                  <strong>{selectedLead.service}</strong>
                </div>

                <div>
                  <small>Lead Source</small>
                  <strong>{selectedLead.source}</strong>
                </div>

                <div>
                  <small>Assigned Vendor</small>
                  <strong>{selectedLead.vendor}</strong>
                </div>

                <div>
                  <small>Assigned Date</small>
                  <strong>{selectedLead.assignedDate}</strong>
                </div>

                <div>
                  <small>Current Stage</small>
                  <strong>
                    <span
                      className={`stage-badge ${selectedLead.stage.toLowerCase()}`}
                    >
                      <span />
                      {selectedLead.stage}
                    </span>
                  </strong>
                </div>
              </div>
            </section>

            {/* Lead status and follow-up */}
            <section className="lead-detail-section">
              <h3>Lead Status &amp; Follow-up</h3>

              <label className="drawer-field">
                <span>Lead Status</span>
                <select
                  value={selectedLead.stage}
                  disabled={!canEditLead}
                  onChange={(event) =>
                    changeLeadStage(event.target.value as LeadStage)
                  }
                >
                  <option value="Called">Called</option>
                  <option value="Interested">Interested</option>
                  <option value="Converted">Converted</option>
                  <option value="Not Interested">Not Interested</option>
                  <option value="WhatsApp">WhatsApp</option>

                </select>
              </label>

              <div className="drawer-followup-grid">
                <label className="drawer-field">
                  <span>Follow-up Date</span>
                  <input
                    type="date"
                    value={selectedLead.followUpDate}
                    disabled={!isAdmin}
                    onChange={(event) => changeFollowUpDate(event.target.value)}
                  />
                </label>

                <label className="drawer-field">
                  <span>Follow-up Time</span>
                  <input
                    type="time"
                    value={selectedLead.followUpTime}
                    disabled={!isAdmin}
                    onChange={(event) => changeFollowUpTime(event.target.value)}
                  />
                </label>
              </div>

              <p className="drawer-hint">
                Next follow-up: <strong>{followUpSummary}</strong>
              </p>

              {!isAdmin && (
                <p className="drawer-hint">
                  You can update the status of your assigned leads and manage notes
                  you added. Follow-up date and time are managed by Admin.
                </p>
              )}
            </section>

            {/* Interaction notes - always the last section */}
            <section className="lead-detail-section notes-section">
              <div className="notes-heading">
                <h3>Interaction Notes</h3>
                <span className="vendor-total">
                  {selectedLead.notes.length}{" "}
                  {selectedLead.notes.length === 1 ? "note" : "notes"}
                </span>
              </div>

              <p>
                Record calls, customer conversations, and follow-up outcomes.
              </p>

              {canEditLead && (
                <div className="note-composer">
                  <textarea
                    value={newNote}
                    onChange={(event) => setNewNote(event.target.value)}
                    placeholder="Example: Called customer, no answer. / Customer answered and discussed photography packages..."
                    rows={4}
                  />

                  <button
                    type="button"
                    className="button-primary"
                    disabled={!newNote.trim()}
                    onClick={addLeadNote}
                  >
                    + Add Note
                  </button>
                </div>
              )}

              {selectedLead.notes.length === 0 ? (
                <div className="notes-empty">
                  No interaction notes have been added yet.
                </div>
              ) : (
                <>
                  <div className="notes-list">
                    {visibleNotes.map((note) => (
                      <article className="note-card" key={note.id}>
                        {editingNoteId === note.id && canCurrentUserEditNote(note) ? (
                          <div className="note-edit-area">
                            <textarea
                              value={editingNoteText}
                              onChange={(event) =>
                                setEditingNoteText(event.target.value)
                              }
                              rows={4}
                            />

                            <div className="note-actions">
                              <button
                                type="button"
                                className="button-secondary"
                                onClick={() => {
                                  setEditingNoteId(null);
                                  setEditingNoteText("");
                                }}
                              >
                                Cancel
                              </button>

                              <button
                                type="button"
                                className="button-primary"
                                disabled={!editingNoteText.trim()}
                                onClick={saveEditedNote}
                              >
                                Save
                              </button>
                            </div>
                          </div>
                        ) : (
                          <p className="note-text">{note.text}</p>
                        )}

                        <div className="note-meta">
                          <span>
                            Added by <strong>{note.addedBy}</strong>
                          </span>

                          <span>
                            {formatNoteDateTime(note.createdAt)}
                            {note.editedAt && (
                              <em className="note-edited">
                                {" "}
                                · edited {formatNoteDateTime(note.editedAt)}
                              </em>
                            )}
                          </span>
                        </div>

                        {canCurrentUserEditNote(note) && editingNoteId !== note.id && (
                          <div className="note-admin-actions">
                            <button
                              type="button"
                              onClick={() => startEditingNote(note)}
                            >
                              Edit
                            </button>

                            <button
                              type="button"
                              className="note-delete"
                              onClick={() => deleteLeadNote(note.id)}
                            >
                              Delete
                            </button>
                          </div>
                        )}
                      </article>
                    ))}
                  </div>

                  {sortedNotes.length > 3 && (
                    <button
                      type="button"
                      className="notes-toggle"
                      onClick={() => setShowAllNotes((current) => !current)}
                    >
                      {showAllNotes
                        ? "Show Less"
                        : `Show More (${sortedNotes.length - 3} older)`}
                    </button>
                  )}
                </>
              )}
            </section>
          </div>

          <footer className="lead-drawer-footer">
            <button
              type="button"
              className="button-secondary"
              onClick={closeLeadDrawer}
            >
              Close
            </button>
          </footer>
        </aside>
      </div>
    );
  }

  function renderVendorDirectory() {
    return (
      <section className="vendor-page">
        <div className="section-heading vendor-page-heading">
          <div>
            <h2>Vendor Directory</h2>
            <p>Explore and manage your service partners.</p>
          </div>
          <div className="vendor-heading-actions">
            <span className="vendor-total">{filteredVendors.length} vendors</span>
            <button className="button-primary" onClick={openAddVendor}>
              + Add Vendor
            </button>
          </div>
        </div>

        <div className="vendor-toolbar">
          <input
            type="search"
            placeholder="Search vendor name or location..."
            value={vendorSearch}
            onChange={(event) => setVendorSearch(event.target.value)}
          />

          <select
            value={selectedService}
            onChange={(event) =>
              setSelectedService(event.target.value as Service | "All")
            }
          >
            <option value="All">All services</option>
            {services.map((service) => (
              <option key={service}>{service}</option>
            ))}
          </select>
        </div>

        <div className="vendor-service-grid">
          {services.map((service) => {
            const serviceVendors = filteredVendors.filter(
              (vendor) => vendor.service === service
            );

            if (serviceVendors.length === 0) return null;

            return (
              <article className="panel vendor-service-card" key={service}>
                <div className="vendor-service-card-top">
                  <div>
                    <span className="vendor-service-icon">
                      {service === "Photography"
                        ? "📷"
                        : service === "Videography"
                          ? "🎥"
                          : service === "Wedding Planning"
                            ? "💍"
                            : "💄"}
                    </span>
                    <h3>{service}</h3>
                    <p>{serviceVendors.length} vendor partners</p>
                  </div>
                </div>

                <div className="vendor-service-list">
                  {serviceVendors.map((vendor) => (
                    <button
                      className="vendor-list-button"
                      key={vendor.id}
                      onClick={() => openVendor(vendor)}
                    >
                      <span>
                        <strong>{vendor.name}</strong>
                        <small>{vendor.location}</small>
                      </span>
                      <span className="vendor-open-arrow">→</span>
                    </button>
                  ))}
                </div>
              </article>
            );
          })}
        </div>

        {filteredVendors.length === 0 && (
          <div className="panel empty-state">No vendors found.</div>
        )}
      </section>
    );
  }

  function renderVendorProfile() {
    if (!selectedVendor) return renderVendorDirectory();

    return (
      <section className="vendor-page">
        <button
          className="vendor-back-button"
          onClick={() => setSelectedVendor(null)}
        >
          ← Back to Vendors
        </button>

        <div className="panel vendor-profile-panel">
          <div className="vendor-profile-heading">
            <div>
              <span className="eyebrow">{selectedVendor.service}</span>
              <h2>{selectedVendor.name}</h2>
              <p>{selectedVendor.location}</p>
            </div>

            <div className="vendor-profile-actions">
              <span className={`vendor-status ${selectedVendor.status.toLowerCase()}`}>
                {selectedVendor.status}
              </span>
              <button
                className="button-danger-outline"
                onClick={() => removeVendor(selectedVendor)}
              >
                Remove Vendor
              </button>
            </div>
          </div>

          <div className="vendor-detail-grid">
            <div>
              <small>Phone Number</small>
              <strong>{selectedVendor.phone || "Not provided"}</strong>
            </div>
            <div>
              <small>Email Address</small>
              <strong>{selectedVendor.email || "Not provided"}</strong>
            </div>
            <div>
              <small>Service</small>
              <strong>{selectedVendor.service}</strong>
            </div>
            <div>
              <small>Location</small>
              <strong>{selectedVendor.location}</strong>
            </div>
            <div>
              <small>Login Username</small>
              <strong>
                {vendorAccounts.find(
                  (account) => account.vendorName === selectedVendor.name
                )?.username ?? "Not set"}
              </strong>
            </div>
          </div>
        </div>

        <div className="vendor-kpi-section">
          <div className="section-heading">
            <div>
              <h2>Vendor Lead Overview</h2>
              <p>Lead performance for {selectedVendor.name}.</p>
            </div>
          </div>

          <div className="vendor-lead-kpi-grid">
            <article className="vendor-lead-kpi">
              <span>Total Leads</span>
              <strong>{leadCounts.total}</strong>
            </article>
            <article className="vendor-lead-kpi">
              <span>Called</span>
              <strong>{leadCounts.called}</strong>
            </article>
            <article className="vendor-lead-kpi">
              <span>Interested</span>
              <strong>{leadCounts.interested}</strong>
            </article>
            <article className="vendor-lead-kpi">
              <span>Converted</span>
              <strong>{leadCounts.converted}</strong>
            </article>
          </div>
        </div>

        <section className="panel vendor-leads-panel">
          <div className="panel-heading">
            <div>
              <h2>Leads Assigned to {selectedVendor.name}</h2>
              <p>View customers assigned to this vendor.</p>
            </div>
          </div>

          <div className="lead-toolbar">
            <input
              type="search"
              placeholder="Search customer name or phone..."
              value={leadSearch}
              onChange={(event) => setLeadSearch(event.target.value)}
            />

            <select
              value={stageFilter}
              onChange={(event) => setStageFilter(event.target.value)}
            >
              <option value="All">All stages</option>
              <option value="Called">Called</option>
              <option value="Interested">Interested</option>
              <option value="Converted">Converted</option>
              <option value="Not Interested">Not Interested</option>
              <option value="WhatsApp">WhatsApp</option>
            </select>
          </div>

          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Customer</th>
                  <th>Phone</th>
                  <th>Service</th>
                  <th>Stage</th>
                  <th>Lead Source</th>
                  <th>Assigned Date</th>
                  <th>Follow-up</th>
                </tr>
              </thead>

              <tbody>
                {filteredVendorLeads.map((lead) => (
                  <tr
                    key={lead.id}
                    className="clickable-lead-row"
                    onClick={() => openLead(lead)}
                  >
                    <td><strong>{lead.name}</strong></td>
                    <td>{lead.phone}</td>
                    <td>{lead.service}</td>
                    <td>
                      <span className={`stage-badge ${lead.stage.toLowerCase()}`}>
                        <span />
                        {lead.stage}
                      </span>
                    </td>
                    <td>{lead.source}</td>
                    <td>{lead.assignedDate}</td>
                    <td>{lead.followUp}</td>
                  </tr>
                ))}

                {filteredVendorLeads.length === 0 && (
                  <tr>
                    <td colSpan={7} className="empty-state">
                      No leads found for this vendor.
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>

          <div className="table-footer">
            Showing {filteredVendorLeads.length} sample records
            <button
              className="text-button"
              onClick={() => {
                setLeadSearch("");
                setStageFilter("All");
              }}
            >
              Clear filters
            </button>
          </div>
        </section>
      </section>
    );
  }

  /* ---------- Admin: "Add Vendor" pop-up ---------- */

  function renderAddVendorModal() {
    if (!showAddVendor) return null;

    return (
      <div className="modal-backdrop" onClick={closeAddVendor}>
        <section
          className="confirm-modal vendor-modal"
          role="dialog"
          aria-modal="true"
          aria-labelledby="add-vendor-title"
          onClick={(event) => event.stopPropagation()}
        >
          <h2 id="add-vendor-title">Add Vendor</h2>

          <p>
            Create a vendor partner and the login they will use to see their own
            leads.
          </p>

          <form className="vendor-form" onSubmit={addVendor}>
            <div className="form-grid">
              <label className="form-field form-field-wide">
                <span>Vendor name *</span>
                <input
                  value={vendorForm.name}
                  onChange={(event) => updateVendorForm({ name: event.target.value })}
                  placeholder="e.g. Lens Studio"
                  autoFocus
                />
              </label>

              <label className="form-field">
                <span>Service *</span>
                <select
                  value={vendorForm.service}
                  onChange={(event) =>
                    updateVendorForm({ service: event.target.value as Service })
                  }
                >
                  {services.map((service) => (
                    <option key={service}>{service}</option>
                  ))}
                </select>
              </label>

              <label className="form-field">
                <span>Location</span>
                <input
                  value={vendorForm.location}
                  onChange={(event) => updateVendorForm({ location: event.target.value })}
                  placeholder="e.g. Bengaluru"
                />
              </label>

              <label className="form-field">
                <span>Phone</span>
                <input
                  value={vendorForm.phone}
                  onChange={(event) => updateVendorForm({ phone: event.target.value })}
                  placeholder="+91 90000 00000"
                />
              </label>

              <label className="form-field">
                <span>Email</span>
                <input
                  type="email"
                  value={vendorForm.email}
                  onChange={(event) => updateVendorForm({ email: event.target.value })}
                  placeholder="name@example.com"
                />
              </label>

              <label className="form-field">
                <span>Login username *</span>
                <input
                  value={vendorForm.username}
                  onChange={(event) => updateVendorForm({ username: event.target.value })}
                  placeholder="e.g. lensstudio"
                  autoCapitalize="none"
                  autoComplete="off"
                />
              </label>

              <label className="form-field">
                <span>Login password *</span>
                <input
                  value={vendorForm.password}
                  onChange={(event) => updateVendorForm({ password: event.target.value })}
                  placeholder="At least 6 characters"
                  autoComplete="off"
                />
              </label>
            </div>

            {vendorFormError && (
              <div className="form-error" role="alert">
                {vendorFormError}
              </div>
            )}

            <div className="modal-actions">
              <button
                type="button"
                className="button-secondary"
                onClick={closeAddVendor}
              >
                Cancel
              </button>

              <button type="submit" className="button-primary">
                + Add Vendor
              </button>
            </div>
          </form>
        </section>
      </div>
    );
  }

  // Vendor dashboard: vendors do not see the Admin dashboard.
  if (userRole === "Vendor") {
    if (!loggedInVendor) {
      return (
        <div className="panel empty-state">
          Vendor account not found. Please sign in again.
          <button className="button-primary" onClick={handleSignOut}>
            Back to Login
          </button>
        </div>
      );
    }

    return (
      <div className="crm vendor-dashboard">
        <aside className="sidebar">
          <a className="brand" href="#vendor-dashboard">
            <img
              className="brand-logo"
              src="/Koc_logo.png"
              alt="King of Cards logo"
            />
            <span>
              <strong>King of Cards</strong>
              <small>VENDOR PORTAL</small>
            </span>
          </a>

          <div className="nav-label">VENDOR WORKSPACE</div>

          <nav className="navigation">
            <button className="nav-item active">
              <span className="nav-icon">▦</span>
              My Dashboard
            </button>

            <button
              className="nav-item"
              onClick={() => {
                document
                  .getElementById("my-vendor-leads")
                  ?.scrollIntoView({ behavior: "smooth" });
              }}
            >
              <span className="nav-icon">☷</span>
              My Leads
            </button>
          </nav>

          <div className="sidebar-bottom">
            <div className="user-profile">
              <div className="avatar">
                {loggedInVendor.name.charAt(0)}
              </div>
              <div className="user-details">
                <strong>{loggedInVendor.name}</strong>
                <span>Vendor Account</span>
              </div>
            </div>

            <button
              className="signout-button"
              onClick={() => setShowSignOut(true)}
            >
              <span>⇥</span> Sign Out
            </button>
          </div>
        </aside>

        <main className="main-content">
          <header className="topbar">
            <div>
              <div className="breadcrumb">VENDOR / DASHBOARD</div>
              <h1>Welcome, {loggedInVendor.name}</h1>
              <p className="page-subtitle">
                View and manage only the leads assigned to your account.
              </p>
            </div>

            <div className="topbar-user">
              <div className="avatar small">
                {loggedInVendor.name.charAt(0)}
              </div>
              <div>
                <strong>{loggedInVendor.name}</strong>
                <span>{loggedInVendor.service}</span>
              </div>
            </div>
          </header>

          <section className="section-block">
            <div className="section-heading">
              <div>
                <h2>My Performance</h2>
                <p>Your assigned lead performance at a glance.</p>
              </div>
            </div>

            <div className="vendor-lead-kpi-grid">
              <article className="vendor-lead-kpi">
                <span>Total Assigned Leads</span>
                <strong>{vendorDashboardCounts.total}</strong>
              </article>

              <article className="vendor-lead-kpi">
                <span>Called</span>
                <strong>{vendorDashboardCounts.contacted}</strong>
              </article>

              <article className="vendor-lead-kpi">
                <span>Interested</span>
                <strong>{vendorDashboardCounts.interested}</strong>
              </article>

              <article className="vendor-lead-kpi">
                <span>Converted</span>
                <strong>{vendorDashboardCounts.converted}</strong>
              </article>
            </div>
          </section>

          <section className="panel vendor-leads-panel" id="my-vendor-leads">
            <div className="panel-heading">
              <div>
                <h2>My Assigned Leads</h2>
                <p>
                  Customer enquiries assigned to {loggedInVendor.name}.
                </p>
              </div>
              <span className="vendor-total">
                {loggedInVendorLeads.length} leads
              </span>
            </div>

            <div className="lead-toolbar">
              <input
                type="search"
                placeholder="Search customer name or phone..."
                value={leadSearch}
                onChange={(event) => setLeadSearch(event.target.value)}
              />

              <select
                value={stageFilter}
                onChange={(event) => setStageFilter(event.target.value)}
              >
                <option value="All">All stages</option>
                <option value="Called">Called</option>
                <option value="Interested">Interested</option>
                <option value="Converted">Converted</option>
                <option value="Not Interested">Not Interested</option>
                <option value="WhatsApp">WhatsApp</option>
              </select>
            </div>

            <div className="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th>Customer</th>
                    <th>Phone</th>
                    <th>Service</th>
                    <th>Stage</th>
                    <th>Lead Source</th>
                    <th>Assigned Date</th>
                    <th>Follow-up</th>
                  </tr>
                </thead>

                <tbody>
                  {filteredLoggedInVendorLeads.map((lead) => (
                    <tr
                      key={lead.id}
                      className="clickable-lead-row"
                      onClick={() => openLead(lead)}
                    >
                      <td><strong>{lead.name}</strong></td>
                      <td>{lead.phone}</td>
                      <td>{lead.service}</td>
                      <td>
                        <span className={`stage-badge ${lead.stage.toLowerCase()}`}>
                          <span />
                          {lead.stage}
                        </span>
                      </td>
                      <td>{lead.source}</td>
                      <td>{lead.assignedDate}</td>
                      <td>{lead.followUp}</td>
                    </tr>
                  ))}

                  {filteredLoggedInVendorLeads.length === 0 && (
                    <tr>
                      <td colSpan={7} className="empty-state">
                        No assigned leads found.
                      </td>
                    </tr>
                  )}
                </tbody>
              </table>
            </div>

            <div className="table-footer">
              Showing {filteredLoggedInVendorLeads.length} assigned leads
              <button
                className="text-button"
                onClick={() => {
                  setLeadSearch("");
                  setStageFilter("All");
                }}
              >
                Clear filters
              </button>
            </div>
          </section>

          <footer className="footer">
            <span>King of Cards · Vendor Portal</span>
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
              aria-labelledby="vendor-signout-title"
              onClick={(event) => event.stopPropagation()}
            >
              <h2 id="vendor-signout-title">Sign out?</h2>

              <p>Are you sure you want to sign out?</p>

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

        {renderLeadDrawer()}
      </div>
    );
  }

  // Admin dashboard starts here.
  return (
    <div className="crm">
      <aside className="sidebar">
        <a
          className="brand"
          href="#dashboard"
          onClick={(event) => {
            event.preventDefault();
            setActivePage("Home");
            setSelectedVendor(null);
          }}
        >
          <img
            className="brand-logo"
            src="/Koc_logo.png"
            alt="King of Cards logo"
          />
          <span>
            <strong>King of Cards</strong>
            <small>CROSS-SELLING CRM ⇄</small>
          </span>
        </a>

        <div className="nav-label">WORKSPACE</div>

        <nav className="navigation">
          {navigation.map((item) =>
            item.name === "Vendors" ? (
              <div className="nav-item-wrap vendors-nav-wrap" key={item.name}>
                <button
                  className={`nav-item ${activePage === "Vendors" ? "active" : ""}`}
                  onClick={() => {
                    setActivePage("Vendors");
                    setSelectedVendor(null);
                  }}
                >
                  <span className="nav-icon">{item.icon}</span>
                  <span>Vendors</span>
                  <span className="vendor-nav-arrow">›</span>
                </button>

                <div className="vendor-service-menu">
                  {services.map((service) => (
                    <div className="vendor-service-menu-wrap" key={service}>
                      <button
                        className="vendor-service-menu-item"
                        onClick={() => {
                          setSelectedService(service);
                          setSelectedVendor(null);
                          setActivePage("Vendors");
                        }}
                      >
                        <span>{service}</span>
                        <span className="vendor-submenu-arrow">›</span>
                      </button>

                      <div className="vendor-submenu">
                        {vendors
                          .filter((vendor) => vendor.service === service)
                          .map((vendor) => (
                            <button
                              className="vendor-submenu-item"
                              key={vendor.id}
                              onClick={() => openVendor(vendor)}
                            >
                              {vendor.name}
                            </button>
                          ))}
                      </div>
                    </div>
                  ))}
                </div>
              </div>
            ) : (
              <button
                key={item.name}
                className={`nav-item ${
                  activePage === item.name ? "active" : ""
                }`}
                onClick={() => {
                  setActivePage(item.name);
                  if (item.name !== "Vendors") {
                    setSelectedVendor(null);
                  }
                }}
              >
                <span className="nav-icon">{item.icon}</span>
                <span>{item.name}</span>
                {item.name === "Leads" && (
                  <span className="nav-count">18</span>
                )}
              </button>
            )
          )}
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
            <span>⇥</span> Sign Out
          </button>
        </div>
      </aside>

      <main className="main-content">
        <header className="topbar">
          <div>
            <div className="breadcrumb">
              {selectedVendor ? "VENDORS / PROFILE" : activePage.toUpperCase()}
            </div>
            <h1>{selectedVendor ? selectedVendor.name : activePage}</h1>
            <p className="page-subtitle">
              {activePage === "Home"
                ? "Welcome back. Here's your cross-selling overview."
                : selectedVendor
                  ? "View vendor information and assigned leads."
                  : `Manage your ${activePage.toLowerCase()} activities.`}
            </p>
          </div>

          <div className="topbar-actions">
            <div className="dashboard-search">
              <span className="search-icon">⌕</span>
              <input
                type="search"
                placeholder="Search leads, vendors, phone..."
                value={search}
                onChange={(event) => setSearch(event.target.value)}
              />

              {search && (
                <button
                  type="button"
                  className="search-clear"
                  onClick={() => setSearch("")}
                  aria-label="Clear search"
                >
                  ×
                </button>
              )}
            </div>

            <button
              className="notification-button"
              aria-label="Notifications"
              onClick={() => alert("Notifications will be available soon.")}
            >
              🔔 <span className="notification-dot" />
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

        {activePage === "Home" && (
          <>
            <section className="section-block">
              <div className="section-heading">
                <div>
                  <h2>Performance Overview</h2>
                  <p>Your key cross-selling metrics at a glance.</p>
                </div>

                <label className="filter-control">
                  <span>Vendor</span>
                  <select
                    value={dashboardVendor}
                    onChange={(event) => setDashboardVendor(event.target.value)}
                  >
                    <option value="All">All vendors</option>
                    {vendors.map((vendor) => (
                      <option key={vendor.id} value={vendor.name}>
                        {vendor.name}
                      </option>
                    ))}
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
                Sample figures for the prototype. Live metrics will come from the backend.
              </p>
            </section>

            <section className="panel leads-panel" id="leads">
              <div className="panel-heading">
                <div>
                  <div className="heading-with-count">
                    <h2>Leads</h2>
                    <span className="count-badge">
                      {dashboardLeads.length} sample leads
                    </span>
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
                  {services.map((service) => (
                    <option key={service}>{service}</option>
                  ))}
                </select>

                <select aria-label="Filter by stage" defaultValue="all">
                  <option value="all">All status</option>
                  <option>Called</option>
                  <option>Interested</option>
                  <option>Converted</option>
                   <option>Not Interested</option>
                    <option value="WhatsApp">WhatsApp</option>
                </select>

                <input
                  type="search"
                  placeholder="Search name, phone, vendor..."
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
                    {dashboardLeads.map((lead) => (
                      <tr
                        key={lead.id}
                        className="clickable-lead-row"
                        onClick={() => openLead(lead)}
                      >
                        <td><strong>{lead.name}</strong></td>
                        <td>{lead.phone}</td>
                        <td>{lead.service}</td>
                        <td>
                          <span className={`stage-badge ${lead.stage.toLowerCase()}`}>
                            <span />
                            {lead.stage}
                          </span>
                        </td>
                        <td>{lead.source}</td>
                        <td>{lead.vendor}</td>
                      </tr>
                    ))}

                    {dashboardLeads.length === 0 && (
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
                Showing {dashboardLeads.length} sample records
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
                    onClick={() => {
                      setActivePage("Vendors");
                      setSelectedVendor(null);
                    }}
                    className="quick-action"
                  >
                    <span className="action-icon">♙</span>
                    <span>
                      <strong>Manage Vendors</strong>
                      <small>View vendor partners</small>
                    </span>
                    <span className="action-arrow">→</span>
                  </button>

                  <button
                    onClick={() => setActivePage("Leads")}
                    className="quick-action"
                  >
                    <span className="action-icon">☷</span>
                    <span>
                      <strong>Manage Leads</strong>
                      <small>Track customer enquiries</small>
                    </span>
                    <span className="action-arrow">→</span>
                  </button>

                  <button
                    onClick={() => setActivePage("Follow-ups")}
                    className="quick-action"
                  >
                    <span className="action-icon">◷</span>
                    <span>
                      <strong>Schedule Follow-up</strong>
                      <small>Plan your next interaction</small>
                    </span>
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
        )}

        {activePage === "Vendors" && (
          selectedVendor ? renderVendorProfile() : renderVendorDirectory()
        )}

        {activePage !== "Home" && activePage !== "Vendors" && (
          <section className="panel placeholder-panel">
            <span className="placeholder-icon">♧</span>
            <h2>{activePage}</h2>
            <p>This section will be implemented in the next phase.</p>
            <button
              className="button-primary"
              onClick={() => setActivePage("Home")}
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
            aria-labelledby="admin-signout-title"
            onClick={(event) => event.stopPropagation()}
          >
            <h2 id="admin-signout-title">Sign out?</h2>

            <p>Are you sure you want to sign out of King of Cards CRM?</p>

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

      {renderLeadDrawer()}

      {renderAddVendorModal()}
    </div>
  );
}

export default App;
