import Login, { type UserRole } from "./Login";
import { useEffect, useState, type FormEvent } from "react";
import BulkUploadModal, { type NewLeadInput } from "./BulkUploadModal";
import VendorPerformance from "./VendorPerformance";
import {
  canImport,
  checkRows,
  downloadTextFile,
  toCsv,
} from "./leadUtils";
import {
  LeadFilterBar,
  applyLeadFilters,
  emptyFilters,
  type LeadFilters,
} from "./LeadFilters";
import "./CRMApp.css";

type Service =
  | "Photography"
  | "Videography"
  | "Wedding Planning"
  | "Make-up Artist";

type LeadStage =
  | "Called"
  | "Interested"
  | "Quotation Sent"
  | "Converted"
  | "Not Interested"
  | "WhatsApp";

type LeadNote = {
  id: number;
  text: string;
  addedBy: string;
  createdAt: string;
  editedAt?: string;
};

// A file attached to a quotation. Saved in the browser for now (data URL).
type QuotationFile = {
  id: number;
  name: string;
  type: string;
  size: number;
  dataUrl: string;
  addedBy: string;
  addedAt: string;
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
  // Who brought this lead: King of Cards gave it to the vendor, or the vendor
  // uploaded it themselves. Old leads without this field count as King of Cards.
  origin?: "King of Cards" | "Vendor";
  // Custom quotation entered by the vendor, in rupees.
  quotationAmount?: number;
  quotationUpdatedAt?: string;
  // The date the quotation was shared with the customer ("2026-11-04").
  quotationDate?: string;
  quotationFiles?: QuotationFile[];
};

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

// Fields of the "Assign Lead" form (admin, on a vendor's profile page).
type AssignForm = {
  name: string;
  phone: string;
  email: string;
  source: string;
};

const emptyAssignForm: AssignForm = {
  name: "",
  phone: "",
  email: "",
  source: "",
};

// The lead statuses, in the order they appear in every dropdown.
const leadStages: LeadStage[] = [
  "Called",
  "Interested",
  "Quotation Sent",
  "Converted",
  "Not Interested",
  "WhatsApp",
];

// "Not Interested" -> "not-interested" (used for the coloured dot in the CSS)
function stageClass(stage: LeadStage) {
  return stage.toLowerCase().replace(/\s+/g, "-");
}

// How many leads are in each status.
function countByStage(list: Lead[]) {
  return {
    total: list.length,
    called: list.filter((lead) => lead.stage === "Called").length,
    interested: list.filter((lead) => lead.stage === "Interested").length,
    quotationSent: list.filter((lead) => lead.stage === "Quotation Sent").length,
    converted: list.filter((lead) => lead.stage === "Converted").length,
    notInterested: list.filter((lead) => lead.stage === "Not Interested").length,
    whatsapp: list.filter((lead) => lead.stage === "WhatsApp").length,
  };
}

function leadKpiItems(
  counts: ReturnType<typeof countByStage>,
  totalLabel: string
) {
  return [
    { label: totalLabel, value: counts.total },
    { label: "Called", value: counts.called },
    { label: "Interested", value: counts.interested },
    { label: "Converted", value: counts.converted },
    { label: "Not Interested", value: counts.notInterested },
    { label: "WhatsApp", value: counts.whatsapp },
  ];
}

// KPI cards shown at the top of each cross-selling section (includes Quotation Sent).
function crossSellKpiItems(list: Lead[]) {
  const counts = countByStage(list);
  return [
    { label: "Total Leads", value: counts.total },
    { label: "Called", value: counts.called },
    { label: "WhatsApp", value: counts.whatsapp },
    { label: "Interested", value: counts.interested },
    { label: "Quotation Sent", value: counts.quotationSent },
    { label: "Converted", value: counts.converted },
    { label: "Not Interested", value: counts.notInterested },
  ];
}

// "2026-10-30" -> "30 Oct 2026"
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

// 45000 -> "₹45,000"
function formatRupees(amount: number) {
  return `₹${amount.toLocaleString("en-IN")}`;
}

// Today as "2026-11-04" (the format a date input uses).
function todayIso() {
  const now = new Date();
  const month = String(now.getMonth() + 1).padStart(2, "0");
  const day = String(now.getDate()).padStart(2, "0");
  return `${now.getFullYear()}-${month}-${day}`;
}

// 1536000 -> "1.5 MB"
function formatFileSize(bytes: number) {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

// Biggest file a vendor can attach for now (files are kept in the browser).
const MAX_ATTACHMENT_BYTES = 1.5 * 1024 * 1024;

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
  // Lead filters: one set for the admin, one for the signed-in vendor.
  const [adminFilters, setAdminFilters] = useState<LeadFilters>(emptyFilters);
  const [vendorFilters, setVendorFilters] = useState<LeadFilters>(emptyFilters);

  // Leads, vendors and vendor logins are kept in React state (and saved in the
  // browser for now) so admin edits update every screen.
  const [leads, setLeads] = usePersistentState<Lead[]>(
    "koc-crm-leads-v2",
    sampleLeads
  );
  const [vendors, setVendors] = usePersistentState<Vendor[]>(
    "koc-crm-vendors-v1",
    sampleVendors
  );
  const [vendorAccounts, setVendorAccounts] = usePersistentState<
    VendorAccount[]
  >("koc-crm-vendor-accounts-v1", initialVendorAccounts);

  // "Add Vendor" form (admin only).
  const [showAddVendor, setShowAddVendor] = useState(false);

  // Bulk upload pop-up (admin and vendor).
  const [showBulkUpload, setShowBulkUpload] = useState(false);
  const [vendorForm, setVendorForm] = useState<VendorForm>(emptyVendorForm);
  const [vendorFormError, setVendorFormError] = useState("");

  // Assigning leads to one vendor, from that vendor's profile (admin only).
  const [showBulkAssign, setShowBulkAssign] = useState(false); // CSV upload locked to this vendor
  const [showAssignOne, setShowAssignOne] = useState(false); // one lead, typed in
  const [showPool, setShowPool] = useState(false); // pick from unassigned leads
  const [assignForm, setAssignForm] = useState<AssignForm>(emptyAssignForm);
  const [assignError, setAssignError] = useState("");
  const [poolSelected, setPoolSelected] = useState<number[]>([]);

  // Lead Details drawer state.
  const [selectedLeadId, setSelectedLeadId] = useState<number | null>(null);
  const [newNote, setNewNote] = useState("");
  const [showAllNotes, setShowAllNotes] = useState(false);
  // What is typed in the quotation amount box.
  const [quotationDraft, setQuotationDraft] = useState("");

  // Admin Leads page: which cross-selling section is selected (null = all leads).
  const [leadsDirection, setLeadsDirection] = useState<"koc" | "vendors" | null>(
    null
  );

  // Vendor dashboard: which section is selected (null = all my leads).
  const [vendorDirection, setVendorDirection] = useState<
    "koc" | "vendors" | null
  >(null);

  // Quotation date box and attachment error message.
  const [quotationDateDraft, setQuotationDateDraft] = useState(todayIso());
  const [attachmentError, setAttachmentError] = useState("");

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

  // Admin: every lead, narrowed by the filter bar and the search box.
  const dashboardLeads = applyLeadFilters(leads, adminFilters).filter((lead) =>
    `${lead.name} ${lead.phone} ${lead.email} ${lead.vendor} ${lead.service}`
      .toLowerCase()
      .includes(search.toLowerCase())
  );

  const adminSources = Array.from(new Set(leads.map((lead) => lead.source))).sort();

  const vendorLeads = selectedVendor
    ? leads.filter((lead) => lead.vendor === selectedVendor.name)
    : [];

  // Leads nobody owns yet, for the service the open vendor offers.
  const poolLeads = selectedVendor
    ? leads.filter(
        (lead) =>
          lead.vendor === "Unassigned" && lead.service === selectedVendor.service
      )
    : [];

  const loggedInVendor = vendors.find(
    (vendor) => vendor.name === currentVendorName
  );

  // This list is restricted to the logged-in vendor.
  const loggedInVendorLeads = leads.filter(
    (lead) => lead.vendor === currentVendorName
  );

  // Vendor: only their own leads, narrowed by the filter bar and search box.
  const filteredLoggedInVendorLeads = applyLeadFilters(
    loggedInVendorLeads,
    vendorFilters
  ).filter((lead) =>
    `${lead.name} ${lead.phone} ${lead.source}`
      .toLowerCase()
      .includes(leadSearch.toLowerCase())
  );

  const vendorSources = Array.from(
    new Set(loggedInVendorLeads.map((lead) => lead.source))
  ).sort();


  // Cross-selling directions. Old leads without "origin" count as King of Cards.
  const isFromKoc = (lead: Lead) => lead.origin !== "Vendor";
  const isFromVendor = (lead: Lead) => lead.origin === "Vendor";

  // Admin view
  const adminKocToVendors = leads.filter(
    (lead) => isFromKoc(lead) && lead.vendor !== "Unassigned"
  );
  const adminVendorsToKoc = leads.filter(isFromVendor);

  // Vendor view (only their own leads)
  const vendorReceivedFromKoc = loggedInVendorLeads.filter(isFromKoc);
  const vendorSentToKoc = loggedInVendorLeads.filter(isFromVendor);

  // Leads page: the leads of the section the admin clicked (or all leads).
  const sectionLeads =
    leadsDirection === "koc"
      ? adminKocToVendors
      : leadsDirection === "vendors"
        ? adminVendorsToKoc
        : leads;
  const sectionIds = new Set(sectionLeads.map((lead) => lead.id));
  // Same filters and search as before, limited to the clicked section.
  const leadsPageList = dashboardLeads.filter((lead) => sectionIds.has(lead.id));

  // Vendor dashboard: the section the vendor clicked (or all their leads).
  const vendorSectionLeads =
    vendorDirection === "koc"
      ? vendorReceivedFromKoc
      : vendorDirection === "vendors"
        ? vendorSentToKoc
        : loggedInVendorLeads;
  const vendorSectionIds = new Set(vendorSectionLeads.map((lead) => lead.id));
  // Same filters and search as before, limited to the clicked section.
  const vendorPageList = filteredLoggedInVendorLeads.filter((lead) =>
    vendorSectionIds.has(lead.id)
  );

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

  const leadCounts = countByStage(vendorLeads);

  function openVendor(vendor: Vendor) {
    setSelectedVendor(vendor);
    setActivePage("Vendors");
    setLeadSearch("");
    setStageFilter("All");
  }

  /* ---------- Lead Details drawer: open / close ---------- */

  function openLead(lead: Lead) {
    setSelectedLeadId(lead.id);
    setQuotationDraft(
      lead.quotationAmount !== undefined ? String(lead.quotationAmount) : ""
    );
    setQuotationDateDraft(lead.quotationDate ?? todayIso());
    setAttachmentError("");
    setNewNote("");
    setShowAllNotes(false);
    setEditingNoteId(null);
    setEditingNoteText("");
  }

  function closeLeadDrawer() {
    setSelectedLeadId(null);
    setQuotationDraft("");
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
    setAdminFilters(emptyFilters);
    setVendorFilters(emptyFilters);
    setShowBulkUpload(false);
    setShowBulkAssign(false);
    setShowAssignOne(false);
    setShowPool(false);
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

    if (adminFilters.vendor === vendor.name) {
      setAdminFilters((current) => ({ ...current, vendor: "All" }));
    }
    setSelectedVendor(null);
  }

  /* ---------- Bulk upload ---------- */

  // The first status every new lead gets. Your list of statuses has no "New",
  // so uploaded leads start as "Called". Add a "New" status later if you want.
  const NEW_LEAD_STAGE: LeadStage = "Called";

  function todayLabel() {
    const now = new Date();
    const months = ["Jan","Feb","Mar","Apr","May","Jun","Jul","Aug","Sep","Oct","Nov","Dec"];
    return `${String(now.getDate()).padStart(2, "0")} ${months[now.getMonth()]} ${now.getFullYear()}`;
  }

  function importLeads(incoming: NewLeadInput[]) {
    if (incoming.length === 0) return;

    // A vendor can only add leads to their own account.
    const byVendor = userRole === "Admin";
    const date = todayLabel();

    setLeads((current) => {
      let nextId = current.reduce((highest, lead) => Math.max(highest, lead.id), 0) + 1;

      const added: Lead[] = incoming.map((row) => ({
        id: nextId++,
        name: row.name,
        email: row.email,
        phone: row.phone,
        service: row.service as Service,
        stage: NEW_LEAD_STAGE,
        source: row.source,
        vendor: byVendor ? row.vendor : currentVendorName,
        assignedDate: date,
        followUp: "Not scheduled",
        followUpDate: "",
        followUpTime: "",
        notes: [],
        origin: byVendor ? "King of Cards" : "Vendor",
      }));

      return [...current, ...added];
    });
  }

  function closeBulkModal() {
    setShowBulkUpload(false);
    setShowBulkAssign(false);
  }

  function renderBulkUploadModal() {
    if (!showBulkUpload && !showBulkAssign) return null;

    return (
      <BulkUploadModal
        mode={userRole}
        services={services}
        vendors={vendors}
        existingLeads={leads}
        currentVendor={
          loggedInVendor
            ? { name: loggedInVendor.name, service: loggedInVendor.service }
            : undefined
        }
        lockedVendor={
          showBulkAssign && selectedVendor
            ? { name: selectedVendor.name, service: selectedVendor.service }
            : undefined
        }
        onImport={importLeads}
        onClose={closeBulkModal}
      />
    );
  }

  /* ---------- Admin: assign leads to the open vendor ---------- */

  // Option 1: type in one lead. The same duplicate check as the bulk upload
  // runs first (same phone or email + same service = already in the system).
  function openAssignOne() {
    if (userRole !== "Admin" || !selectedVendor) return;

    setAssignForm(emptyAssignForm);
    setAssignError("");
    setShowAssignOne(true);
  }

  function closeAssignOne() {
    setShowAssignOne(false);
    setAssignForm(emptyAssignForm);
    setAssignError("");
  }

  function updateAssignForm(patch: Partial<AssignForm>) {
    setAssignForm((current) => ({ ...current, ...patch }));
    setAssignError("");
  }

  function assignOneLead(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (userRole !== "Admin" || !selectedVendor) return;

    const name = assignForm.name.trim();
    if (!name) {
      setAssignError("Please enter the customer name.");
      return;
    }

    const [row] = checkRows(
      [
        {
          line: 2,
          name,
          phone: assignForm.phone,
          email: assignForm.email,
          service: "",
          source: assignForm.source.trim(),
          vendor: "",
        },
      ],
      {
        services,
        existing: leads,
        vendors,
        fixedVendor: {
          name: selectedVendor.name,
          service: selectedVendor.service,
        },
        assignment: "fixed",
        revealHolder: true,
      }
    );

    if (!canImport(row)) {
      setAssignError(row.reason || "This lead cannot be added.");
      return;
    }

    importLeads([
      {
        name: row.name,
        email: row.email,
        phone: row.phone,
        service: row.service,
        vendor: selectedVendor.name,
        source: row.source || "Manual assignment",
      },
    ]);

    closeAssignOne();
  }

  // Option 2: tick several unassigned leads and give them to this vendor.
  function openPool() {
    if (userRole !== "Admin" || !selectedVendor) return;

    setPoolSelected([]);
    setShowPool(true);
  }

  function closePool() {
    setShowPool(false);
    setPoolSelected([]);
  }

  function togglePoolLead(id: number) {
    setPoolSelected((current) =>
      current.includes(id)
        ? current.filter((item) => item !== id)
        : [...current, id]
    );
  }

  function assignPoolLeads() {
    if (userRole !== "Admin" || !selectedVendor || poolSelected.length === 0) {
      return;
    }

    const date = todayLabel();
    const vendorName = selectedVendor.name;

    setLeads((current) =>
      current.map((lead) =>
        poolSelected.includes(lead.id) && lead.vendor === "Unassigned"
          ? {
              ...lead,
              vendor: vendorName,
              assignedDate: date,
              origin: "King of Cards",
            }
          : lead
      )
    );

    closePool();
  }

  /* ---------- Lead Details drawer: edits ---------- */

  // The admin can edit every lead. A vendor can edit only the leads assigned
  // to them, because the vendor is the one who calls the customer and
  // handles the lead.
  function canEditLead(lead: Lead | null) {
    if (!lead) return false;
    return userRole === "Admin" || lead.vendor === currentVendorName;
  }

  // Notes are signed with the writer's name.
  const authorName = userRole === "Admin" ? "CRM Admin" : currentVendorName;

  // The admin can change any note. A vendor can change only their own notes.
  function canModifyNote(note: LeadNote) {
    return (
      canEditLead(selectedLead) &&
      (userRole === "Admin" || note.addedBy === currentVendorName)
    );
  }

  function updateSelectedLead(patch: Partial<Lead>) {
    if (!canEditLead(selectedLead) || selectedLeadId === null) return;

    setLeads((currentLeads) =>
      currentLeads.map((lead) =>
        lead.id === selectedLeadId ? { ...lead, ...patch } : lead
      )
    );
  }

  function changeLeadStage(stage: LeadStage) {
    if (!selectedLead) return;

    updateSelectedLead({
      stage,
      followUp:
        stage === "Converted"
          ? "Completed"
          : formatFollowUpDate(selectedLead.followUpDate),
    });
  }

  function changeFollowUpDate(date: string) {
    if (!selectedLead) return;

    updateSelectedLead({
      followUpDate: date,
      followUp:
        selectedLead.stage === "Converted"
          ? "Completed"
          : formatFollowUpDate(date),
    });
  }

  // Saves the typed amount on the lead. If the lead is still at an early
  // stage, it also moves to "Quotation Sent" automatically.
  function saveQuotation() {
    if (!selectedLead || !canEditLead(selectedLead)) return;

    const amount = Number(quotationDraft);
    if (!quotationDraft.trim() || !Number.isFinite(amount) || amount <= 0) {
      return;
    }

    const earlyStage =
      selectedLead.stage === "Called" ||
      selectedLead.stage === "Interested" ||
      selectedLead.stage === "WhatsApp";

    updateSelectedLead({
      quotationAmount: amount,
      quotationDate: quotationDateDraft || todayIso(),
      quotationUpdatedAt: new Date().toISOString(),
      ...(earlyStage
        ? {
            stage: "Quotation Sent" as LeadStage,
            followUp: formatFollowUpDate(selectedLead.followUpDate),
          }
        : {}),
    });
  }

  // Clears the quotation (the status is left as it is).
  function clearQuotation() {
    if (!selectedLead || !canEditLead(selectedLead)) return;
    if (!window.confirm("Remove this quotation?")) return;

    updateSelectedLead({
      quotationAmount: undefined,
      quotationDate: undefined,
      quotationUpdatedAt: undefined,
    });
    setQuotationDraft("");
    setQuotationDateDraft(todayIso());
  }

  // Reads each chosen file and attaches it to the open lead.
  function addQuotationFiles(files: FileList | null) {
    if (!files || !selectedLead || !canEditLead(selectedLead)) return;

    const leadId = selectedLead.id;
    setAttachmentError("");

    Array.from(files).forEach((file) => {
      if (file.size > MAX_ATTACHMENT_BYTES) {
        setAttachmentError(`"${file.name}" is bigger than 1.5 MB.`);
        return;
      }

      const reader = new FileReader();

      reader.onload = () => {
        const attachment: QuotationFile = {
          id: Date.now() + Math.floor(Math.random() * 1000),
          name: file.name,
          type: file.type,
          size: file.size,
          dataUrl: String(reader.result),
          addedBy: authorName,
          addedAt: new Date().toISOString(),
        };

        setLeads((current) =>
          current.map((lead) =>
            lead.id === leadId
              ? {
                  ...lead,
                  quotationFiles: [...(lead.quotationFiles ?? []), attachment],
                }
              : lead
          )
        );
      };

      reader.readAsDataURL(file);
    });
  }

  function removeQuotationFile(fileId: number) {
    if (!selectedLead || !canEditLead(selectedLead)) return;
    if (!window.confirm("Remove this attachment?")) return;

    updateSelectedLead({
      quotationFiles: (selectedLead.quotationFiles ?? []).filter(
        (file) => file.id !== fileId
      ),
    });
  }

  function addLeadNote() {
    if (!canEditLead(selectedLead) || !selectedLead || !newNote.trim()) return;

    const note: LeadNote = {
      id: Date.now(),
      text: newNote.trim(),
      addedBy: authorName,
      createdAt: new Date().toISOString(),
    };

    updateSelectedLead({
      notes: [...selectedLead.notes, note],
    });

    setNewNote("");
  }

  function startEditingNote(note: LeadNote) {
    if (!canModifyNote(note)) return;

    setEditingNoteId(note.id);
    setEditingNoteText(note.text);
  }

  function saveEditedNote() {
    if (
      !canEditLead(selectedLead) ||
      !selectedLead ||
      editingNoteId === null ||
      !editingNoteText.trim()
    ) {
      return;
    }

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
    const note = selectedLead?.notes.find((item) => item.id === noteId);
    if (!selectedLead || !note || !canModifyNote(note)) return;

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

    const canEdit = canEditLead(selectedLead);

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
                {!canEdit && <span className="view-only-badge">View only</span>}
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
                      className={`stage-badge ${stageClass(selectedLead.stage)}`}
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
                  disabled={!canEdit}
                  onChange={(event) =>
                    changeLeadStage(event.target.value as LeadStage)
                  }
                >
                  {leadStages.map((stage) => (
                    <option key={stage} value={stage}>
                      {stage}
                    </option>
                  ))}
                </select>
              </label>

              <div className="drawer-followup-grid">
                <label className="drawer-field">
                  <span>Follow-up Date</span>
                  <input
                    type="date"
                    value={selectedLead.followUpDate}
                    disabled={!canEdit}
                    onChange={(event) => changeFollowUpDate(event.target.value)}
                  />
                </label>

                <label className="drawer-field">
                  <span>Follow-up Time</span>
                  <input
                    type="time"
                    value={selectedLead.followUpTime}
                    disabled={!canEdit}
                    onChange={(event) =>
                      updateSelectedLead({ followUpTime: event.target.value })
                    }
                  />
                </label>
              </div>

              <p className="drawer-hint">
                Next follow-up: <strong>{followUpSummary}</strong>
              </p>

              {!canEdit && (
                <p className="drawer-hint">
                  Only the vendor this lead is assigned to, or an admin, can
                  change it.
                </p>
              )}
            </section>

            {/* Quotation */}
            <section className="lead-detail-section">
              <h3>Quotation</h3>

              {selectedLead.quotationAmount !== undefined ? (
                <p className="quotation-current">
                  Current quotation:{" "}
                  <strong>{formatRupees(selectedLead.quotationAmount)}</strong>
                  {selectedLead.quotationUpdatedAt && (
                    <span>
                      {" "}
                      · updated {formatNoteDateTime(selectedLead.quotationUpdatedAt)}
                    </span>
                  )}
                  <br />
                  Shared on:{" "}
                  <strong>
                    {selectedLead.quotationDate
                      ? formatFollowUpDate(selectedLead.quotationDate)
                      : "Not recorded"}
                  </strong>
                </p>
              ) : (
                <p className="drawer-hint">No quotation has been added yet.</p>
              )}

              {canEdit && (
                <div className="quotation-row">
                  <label className="drawer-field">
                    <span>Quotation amount (₹)</span>
                    <input
                      type="number"
                      min="0"
                      step="1"
                      value={quotationDraft}
                      onChange={(event) => setQuotationDraft(event.target.value)}
                      placeholder="e.g. 45000"
                    />
                  </label>

                  <label className="drawer-field">
                    <span>Date shared</span>
                    <input
                      type="date"
                      value={quotationDateDraft}
                      onChange={(event) => setQuotationDateDraft(event.target.value)}
                    />
                  </label>

                  <button
                    type="button"
                    className="button-primary"
                    disabled={
                      !quotationDraft.trim() || Number(quotationDraft) <= 0
                    }
                    onClick={saveQuotation}
                  >
                    Save Quotation
                  </button>

                  {selectedLead.quotationAmount !== undefined && (
                    <button
                      type="button"
                      className="button-secondary"
                      onClick={clearQuotation}
                    >
                      Remove
                    </button>
                  )}
                </div>
              )}

              {/* Attachments: the vendor uploads the quotation file here */}
              <div className="attachments">
                <h4>Attachments</h4>

                {(selectedLead.quotationFiles ?? []).length === 0 ? (
                  <p className="drawer-hint">No files attached yet.</p>
                ) : (
                  <ul className="attachment-list">
                    {(selectedLead.quotationFiles ?? []).map((file) => (
                      <li key={file.id}>
                        <div>
                          <a
                            className="attachment-name"
                            href={file.dataUrl}
                            download={file.name}
                          >
                            {file.name}
                          </a>
                          <span className="attachment-meta">
                            {formatFileSize(file.size)} · {file.addedBy} ·{" "}
                            {formatNoteDateTime(file.addedAt)}
                          </span>
                        </div>

                        {canEdit && (
                          <button
                            type="button"
                            className="note-delete"
                            onClick={() => removeQuotationFile(file.id)}
                          >
                            Remove
                          </button>
                        )}
                      </li>
                    ))}
                  </ul>
                )}

                {canEdit && (
                  <label className="attachment-upload">
                    <span>+ Attach file</span>
                    <input
                      type="file"
                      multiple
                      accept=".pdf,.doc,.docx,.xls,.xlsx,.png,.jpg,.jpeg"
                      onChange={(event) => {
                        addQuotationFiles(event.target.files);
                        event.target.value = "";
                      }}
                    />
                  </label>
                )}

                {attachmentError && (
                  <div className="form-error" role="alert">
                    {attachmentError}
                  </div>
                )}
              </div>
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

              {canEdit && (
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
                        {editingNoteId === note.id && canModifyNote(note) ? (
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

                        {canModifyNote(note) && editingNoteId !== note.id && (
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

  /* ---------- KPI strip + two clickable sections (vendor dashboard) ---------- */

  function renderDirectionBlock(
    list: Lead[],
    active: "koc" | "vendors" | null,
    onPick: (next: "koc" | "vendors" | null) => void,
    kocLabel: string,
    kocCount: number,
    vendorsLabel: string,
    vendorsCount: number
  ) {
    const counts = countByStage(list);
    const kpis = [
      { label: "Total", value: counts.total },
      { label: "Pending", value: counts.called + counts.whatsapp },
      { label: "Interested", value: counts.interested },
      { label: "Quotation Sent", value: counts.quotationSent },
      { label: "Converted", value: counts.converted },
      { label: "Not Interested", value: counts.notInterested },
    ];
    const name =
      active === "koc" ? kocLabel : active === "vendors" ? vendorsLabel : "All leads";

    return (
      <>
        <div className="direction-kpis">
          <span className="direction-kpis-label">{name}</span>
          <div className="direction-kpi-row">
            {kpis.map((item) => (
              <div className="direction-kpi" key={item.label}>
                <span>{item.label}</span>
                <strong>{item.value}</strong>
              </div>
            ))}
          </div>
        </div>

        <div className="direction-switch">
          <button
            type="button"
            className={`direction-card ${active === "koc" ? "active" : ""}`}
            onClick={() => onPick(active === "koc" ? null : "koc")}
          >
            <span className="direction-title">{kocLabel}</span>
            <strong>{kocCount}</strong>
          </button>

          <button
            type="button"
            className={`direction-card ${active === "vendors" ? "active" : ""}`}
            onClick={() => onPick(active === "vendors" ? null : "vendors")}
          >
            <span className="direction-title">{vendorsLabel}</span>
            <strong>{vendorsCount}</strong>
          </button>
        </div>
      </>
    );
  }

  /* ---------- Cross-selling panel (used by admin and vendor) ---------- */

  function renderCrossSellPanel(
    title: string,
    description: string,
    list: Lead[],
    emptyText: string,
    vendorColumnLabel?: string // pass a label to show the Vendor column
  ) {
    const columns = vendorColumnLabel ? 7 : 6;

    return (
      <section className="panel">
        <div className="panel-heading">
          <div>
            <div className="heading-with-count">
              <h2>{title}</h2>
              <span className="count-badge">
                {list.length} {list.length === 1 ? "lead" : "leads"}
              </span>
            </div>
            <p>{description}</p>
          </div>
        </div>

        {/* Lead status KPIs for this section */}
        <div className="crosssell-kpi-grid">
          {crossSellKpiItems(list).map((item) => (
            <article className="vendor-lead-kpi" key={item.label}>
              <span>{item.label}</span>
              <strong>{item.value}</strong>
            </article>
          ))}
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
                {vendorColumnLabel && <th>{vendorColumnLabel}</th>}
                <th>Date</th>
              </tr>
            </thead>

            <tbody>
              {list.map((lead) => (
                <tr
                  key={lead.id}
                  className="clickable-lead-row"
                  onClick={() => openLead(lead)}
                >
                  <td><strong>{lead.name}</strong></td>
                  <td>{lead.phone}</td>
                  <td>{lead.service}</td>
                  <td>
                    <span className={`stage-badge ${stageClass(lead.stage)}`}>
                      <span />
                      {lead.stage}
                    </span>
                  </td>
                  <td>{lead.source}</td>
                  {vendorColumnLabel && <td>{lead.vendor}</td>}
                  <td>{lead.assignedDate}</td>
                </tr>
              ))}

              {list.length === 0 && (
                <tr>
                  <td colSpan={columns} className="empty-state">
                    {emptyText}
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      </section>
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
            {leadKpiItems(leadCounts, "Total Leads").map((item) => (
              <article className="vendor-lead-kpi" key={item.label}>
                <span>{item.label}</span>
                <strong>{item.value}</strong>
              </article>
            ))}
          </div>
        </div>

        <section className="panel vendor-leads-panel">
          <div className="panel-heading">
            <div>
              <h2>Leads Assigned to {selectedVendor.name}</h2>
              <p>View customers assigned to this vendor.</p>
            </div>

            {/* Admin: give this vendor more leads, one by one or in bulk. */}
            <div className="panel-actions">
              <button className="button-secondary" onClick={openPool}>
                Pick from Unassigned ({poolLeads.length})
              </button>
              <button
                className="button-secondary"
                onClick={() => setShowBulkAssign(true)}
              >
                ↑ Bulk Upload
              </button>
              <button className="button-primary" onClick={openAssignOne}>
                + Assign Lead
              </button>
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
              {leadStages.map((stage) => (
                <option key={stage} value={stage}>
                  {stage}
                </option>
              ))}
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
                      <span className={`stage-badge ${stageClass(lead.stage)}`}>
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

  /* ---------- Admin: "Assign Lead" pop-up (one lead) ---------- */

  function renderAssignOneModal() {
    if (!showAssignOne || !selectedVendor) return null;

    return (
      <div className="modal-backdrop" onClick={closeAssignOne}>
        <section
          className="confirm-modal vendor-modal"
          role="dialog"
          aria-modal="true"
          aria-labelledby="assign-one-title"
          onClick={(event) => event.stopPropagation()}
        >
          <h2 id="assign-one-title">Assign Lead to {selectedVendor.name}</h2>

          <p>
            Service: {selectedVendor.service}. The phone number and email are
            checked so the same customer is not given twice.
          </p>

          <form className="vendor-form" onSubmit={assignOneLead}>
            <div className="form-grid">
              <label className="form-field form-field-wide">
                <span>Customer name *</span>
                <input
                  value={assignForm.name}
                  onChange={(event) => updateAssignForm({ name: event.target.value })}
                  placeholder="e.g. Asha Rao"
                  autoFocus
                />
              </label>

              <label className="form-field">
                <span>Phone</span>
                <input
                  value={assignForm.phone}
                  onChange={(event) => updateAssignForm({ phone: event.target.value })}
                  placeholder="98450 12345"
                />
              </label>

              <label className="form-field">
                <span>Email</span>
                <input
                  type="email"
                  value={assignForm.email}
                  onChange={(event) => updateAssignForm({ email: event.target.value })}
                  placeholder="name@example.com"
                />
              </label>

              <label className="form-field form-field-wide">
                <span>Lead source</span>
                <input
                  value={assignForm.source}
                  onChange={(event) => updateAssignForm({ source: event.target.value })}
                  placeholder="e.g. Wedding card order"
                />
              </label>
            </div>

            {assignError && (
              <div className="form-error" role="alert">
                {assignError}
              </div>
            )}

            <div className="modal-actions">
              <button
                type="button"
                className="button-secondary"
                onClick={closeAssignOne}
              >
                Cancel
              </button>

              <button type="submit" className="button-primary">
                Assign Lead
              </button>
            </div>
          </form>
        </section>
      </div>
    );
  }

  /* ---------- Admin: "Pick from Unassigned" pop-up (many leads) ---------- */

  function renderPoolModal() {
    if (!showPool || !selectedVendor) return null;

    const allSelected =
      poolLeads.length > 0 && poolSelected.length === poolLeads.length;

    return (
      <div className="modal-backdrop" onClick={closePool}>
        <section
          className="confirm-modal bulk-modal"
          role="dialog"
          aria-modal="true"
          aria-labelledby="pool-title"
          onClick={(event) => event.stopPropagation()}
        >
          <h2 id="pool-title">Unassigned {selectedVendor.service} leads</h2>

          <p>Tick the leads you want to give to {selectedVendor.name}.</p>

          {poolLeads.length === 0 ? (
            <div className="notes-empty">
              There are no unassigned {selectedVendor.service} leads right now.
              Upload a file and choose "Leave unassigned", or use the bulk upload
              here to add leads straight to {selectedVendor.name}.
            </div>
          ) : (
            <div className="table-wrap bulk-table">
              <table>
                <thead>
                  <tr>
                    <th>
                      <input
                        type="checkbox"
                        aria-label="Select all"
                        checked={allSelected}
                        onChange={(event) =>
                          setPoolSelected(
                            event.target.checked
                              ? poolLeads.map((lead) => lead.id)
                              : []
                          )
                        }
                      />
                    </th>
                    <th>Name</th>
                    <th>Phone</th>
                    <th>Email</th>
                    <th>Source</th>
                  </tr>
                </thead>

                <tbody>
                  {poolLeads.map((lead) => (
                    <tr key={lead.id}>
                      <td>
                        <input
                          type="checkbox"
                          aria-label={`Select ${lead.name}`}
                          checked={poolSelected.includes(lead.id)}
                          onChange={() => togglePoolLead(lead.id)}
                        />
                      </td>
                      <td><strong>{lead.name}</strong></td>
                      <td>{lead.phone}</td>
                      <td>{lead.email}</td>
                      <td>{lead.source}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}

          <div className="modal-actions">
            <button className="button-secondary" onClick={closePool}>
              Cancel
            </button>

            <button
              className="button-primary"
              disabled={poolSelected.length === 0}
              onClick={assignPoolLeads}
            >
              {poolSelected.length > 0
                ? `Assign ${poolSelected.length} lead${poolSelected.length === 1 ? "" : "s"}`
                : "Assign leads"}
            </button>
          </div>
        </section>
      </div>
    );
  }

  /* ---------- Admin: lead filters and the Leads page ---------- */

  function renderAdminFilterBar(
    shown: number = dashboardLeads.length,
    total: number = leads.length
  ) {
    return (
      <LeadFilterBar
        mode="Admin"
        filters={adminFilters}
        onChange={setAdminFilters}
        onClear={() => setSearch("")}
        searchActive={search.trim() !== ""}
        services={services}
        vendors={vendors}
        sources={adminSources}
        shown={shown}
        total={total}
      >
        <input
          type="search"
          placeholder="Name, phone, email, vendor..."
          value={search}
          onChange={(event) => setSearch(event.target.value)}
        />
      </LeadFilterBar>
    );
  }

  // Downloads exactly the leads the filters are showing.
  function exportFilteredLeads() {
    downloadTextFile(
      "leads-export.csv",
      toCsv([
        ["Name", "Phone", "Email", "Service", "Stage", "Source", "Vendor", "Assigned Date", "Follow-up"],
        ...leadsPageList.map((lead) => [
          lead.name, lead.phone, lead.email, lead.service, lead.stage,
          lead.source, lead.vendor, lead.assignedDate, lead.followUp,
        ]),
      ])
    );
  }

  function renderAdminLeadsPage() {
    const sectionCounts = countByStage(sectionLeads);
    const sectionKpis = [
      { label: "Total", value: sectionCounts.total },
      { label: "Pending", value: sectionCounts.called + sectionCounts.whatsapp },
      { label: "Interested", value: sectionCounts.interested },
      { label: "Quotation Sent", value: sectionCounts.quotationSent },
      { label: "Converted", value: sectionCounts.converted },
      { label: "Not Interested", value: sectionCounts.notInterested },
    ];
    const sectionName =
      leadsDirection === "koc"
        ? "King of Cards → Vendors"
        : leadsDirection === "vendors"
          ? "Vendors → King of Cards"
          : "All leads";

    return (
      <>
      {/* KPIs of the selected section, at the very top */}
      <div className="direction-kpis">
        <span className="direction-kpis-label">{sectionName}</span>
        <div className="direction-kpi-row">
          {sectionKpis.map((item) => (
            <div className="direction-kpi" key={item.label}>
              <span>{item.label}</span>
              <strong>{item.value}</strong>
            </div>
          ))}
        </div>
      </div>

      {/* Two sections side by side. Click one to see its KPIs and leads. */}
      <div className="direction-switch">
        <button
          type="button"
          className={`direction-card ${leadsDirection === "koc" ? "active" : ""}`}
          onClick={() =>
            setLeadsDirection((current) => (current === "koc" ? null : "koc"))
          }
        >
          <span className="direction-title">King of Cards → Vendors</span>
          <strong>{adminKocToVendors.length}</strong>
          <small>Leads you gave to vendors</small>
        </button>

        <button
          type="button"
          className={`direction-card ${leadsDirection === "vendors" ? "active" : ""}`}
          onClick={() =>
            setLeadsDirection((current) =>
              current === "vendors" ? null : "vendors"
            )
          }
        >
          <span className="direction-title">Vendors → King of Cards</span>
          <strong>{adminVendorsToKoc.length}</strong>
          <small>Leads vendors brought to you</small>
        </button>
      </div>

      <section className="panel leads-panel">
        <div className="panel-heading">
          <div>
            <div className="heading-with-count">
              <h2>{leadsDirection === null ? "All Leads" : sectionName}</h2>
              <span className="count-badge">
                {leadsPageList.length} of {sectionLeads.length} leads
              </span>
            </div>
            <p>
              {leadsDirection === null
                ? "Click a section above to see only its leads and KPIs."
                : "Click the selected section again to show all leads."}
            </p>
          </div>

          <div className="panel-actions">
            <button
              className="button-secondary"
              onClick={exportFilteredLeads}
              disabled={leadsPageList.length === 0}
            >
              ↓ Export CSV
            </button>
            <button
              className="button-secondary"
              onClick={() => setShowBulkUpload(true)}
            >
              ↑ Bulk Upload
            </button>
          </div>
        </div>

        {renderAdminFilterBar(leadsPageList.length, sectionLeads.length)}

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
                <th>Assigned Date</th>
                <th>Follow-up</th>
              </tr>
            </thead>

            <tbody>
              {leadsPageList.map((lead) => (
                <tr
                  key={lead.id}
                  className="clickable-lead-row"
                  onClick={() => openLead(lead)}
                >
                  <td><strong>{lead.name}</strong></td>
                  <td>{lead.phone}</td>
                  <td>{lead.service}</td>
                  <td>
                    <span className={`stage-badge ${stageClass(lead.stage)}`}>
                      <span />
                      {lead.stage}
                    </span>
                  </td>
                  <td>{lead.source}</td>
                  <td>{lead.vendor}</td>
                  <td>{lead.assignedDate}</td>
                  <td>{lead.followUp}</td>
                </tr>
              ))}

              {leadsPageList.length === 0 && (
                <tr>
                  <td colSpan={8} className="empty-state">
                    No leads match these filters.
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      </section>
      </>
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

          {/* KPIs on top, then the two sections. Click one to see its leads. */}
          {renderDirectionBlock(
            vendorSectionLeads,
            vendorDirection,
            setVendorDirection,
            "King of Cards → Me",
            vendorReceivedFromKoc.length,
            "Me → King of Cards",
            vendorSentToKoc.length
          )}

          <section className="panel vendor-leads-panel" id="my-vendor-leads">
            <div className="panel-heading">
              <div>
                <h2>My Assigned Leads</h2>
                <p>
                  Customer enquiries assigned to {loggedInVendor.name}.
                </p>
              </div>
              <div className="vendor-heading-actions">
                <span className="vendor-total">
                  {loggedInVendorLeads.length} leads
                </span>
                <button
                  className="button-secondary"
                  onClick={() => setShowBulkUpload(true)}
                >
                  ↑ Upload My Leads
                </button>
              </div>
            </div>

            <LeadFilterBar
              mode="Vendor"
              filters={vendorFilters}
              onChange={setVendorFilters}
              onClear={() => setLeadSearch("")}
              searchActive={leadSearch.trim() !== ""}
              services={services}
              vendors={vendors}
              sources={vendorSources}
              lockedService={loggedInVendor.service}
              shown={vendorPageList.length}
              total={vendorSectionLeads.length}
            >
              <input
                type="search"
                placeholder="Name, phone or source..."
                value={leadSearch}
                onChange={(event) => setLeadSearch(event.target.value)}
              />
            </LeadFilterBar>

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
                  {vendorPageList.map((lead) => (
                    <tr
                      key={lead.id}
                      className="clickable-lead-row"
                      onClick={() => openLead(lead)}
                    >
                      <td><strong>{lead.name}</strong></td>
                      <td>{lead.phone}</td>
                      <td>{lead.service}</td>
                      <td>
                        <span className={`stage-badge ${stageClass(lead.stage)}`}>
                          <span />
                          {lead.stage}
                        </span>
                      </td>
                      <td>{lead.source}</td>
                      <td>{lead.assignedDate}</td>
                      <td>{lead.followUp}</td>
                    </tr>
                  ))}

                  {vendorPageList.length === 0 && (
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
              Showing {vendorPageList.length} assigned leads
              <button
                className="text-button"
                onClick={() => {
                  setLeadSearch("");
                  setVendorFilters(emptyFilters);
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

        {renderBulkUploadModal()}
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
                  <span className="nav-count">{leads.length}</span>
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
                    value={adminFilters.vendor}
                    onChange={(event) => {
                      const name = event.target.value;
                      const picked = vendors.find((item) => item.name === name);
                      setAdminFilters((current) => ({
                        ...current,
                        vendor: name,
                        service: picked ? picked.service : current.service,
                        assignment: "All",
                      }));
                    }}
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
                    onClick={() => setShowBulkUpload(true)}
                  >
                    ↑ Bulk Upload
                  </button>
                  <button
                    className="button-primary"
                    onClick={() => alert("The Add Lead form is coming next.")}
                  >
                    + Add Lead
                  </button>
                </div>
              </div>

              {renderAdminFilterBar()}

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
                          <span className={`stage-badge ${stageClass(lead.stage)}`}>
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

            {renderCrossSellPanel(
              "Leads from King of Cards → Vendors",
              "Customers King of Cards has passed on to vendor partners.",
              adminKocToVendors,
              "No leads have been given to vendors yet.",
              "Given to"
            )}

            {renderCrossSellPanel(
              "Leads from Vendors → King of Cards",
              "Customers vendors have brought in for King of Cards.",
              adminVendorsToKoc,
              "No vendor has uploaded leads yet.",
              "Brought by"
            )}

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

        {activePage === "Reports" && (
          <VendorPerformance
            leads={leads}
            vendors={vendors}
            services={services}
            onOpenVendor={(name) => {
              const vendor = vendors.find((item) => item.name === name);
              if (vendor) openVendor(vendor);
            }}
          />
        )}

        {activePage === "Leads" && renderAdminLeadsPage()}

        {activePage !== "Home" &&
          activePage !== "Vendors" &&
          activePage !== "Reports" &&
          activePage !== "Leads" && (
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

      {renderAssignOneModal()}

      {renderPoolModal()}

      {renderBulkUploadModal()}
    </div>
  );
}

export default App;