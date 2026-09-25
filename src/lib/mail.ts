import designSamples from "../data/design-message-samples.json";
import fixture from "../data/email-app-mock-data.json";
export type Attachment = {
  id: string;
  filename: string;
  mimeType: string;
  sizeBytes: number;
  downloadState: "notDownloaded" | "available";
};
export type Account = {
  id: string;
  name: string;
  address: string;
  protocol: "imap" | "jmap";
  color: string;
  incoming?: string;
  outgoing?: string;
  incomingPort?: number;
  outgoingPort?: number;
  security?: string;
  outgoingSecurity?: string;
  requireAuth?: boolean;
};
export type Folder = {
  id: string;
  accountId: string;
  name: string;
  parentId: string | null;
  role: string;
};
export type Message = {
  id: string;
  accountId: string;
  folderId: string;
  from: { name: string; address: string };
  to: string[];
  cc: string[];
  bcc?: string[];
  subject: string;
  preview: string;
  bodyText: string;
  bodyHtml: string | null;
  receivedAt: string;
  isRead: boolean;
  isNewSinceLastVisit: boolean;
  attachments: Attachment[];
  relatedGroupId: string | null;
  isDraft?: boolean;
};
export type MailState = {
  demoDataVersion?: number;
  accounts: Account[];
  folders: Folder[];
  messages: Message[];
  selectedFolder: string;
  features: { unified: boolean; threads: boolean };
  downloadPolicy: "onDemand" | "automatic";
  offline: boolean;
};
export const SNAPSHOT = fixture.clock;
const communityAccount: Account = {
  id: "community",
  name: "Rhea · Community",
  address: "rhea@community.example.net",
  protocol: "imap",
  color: "purple",
};

// Deterministic IDs let older saved demos receive samples once without duplicates.
export function populateDemoMailbox(
  state: MailState,
  account: Account,
): MailState {
  const folders = [...state.folders];
  for (const [role, name, parent] of [
    ["inbox", "Inbox", null],
    ["drafts", "Drafts", null],
    ["sent", "Sent", null],
    ["archive", "Archive", null],
    ["spam", "Spam", null],
    ["trash", "Trash", null],
    ["travel", "Travel", null],
    ["receipts", "Receipts", null],
    ["trips", "Upcoming trips", "travel"],
  ]) {
    if (!folders.some((f) => f.id === `${account.id}-${role}`))
      folders.push({
        id: `${account.id}-${role}`,
        accountId: account.id,
        name: name!,
        role: ["travel", "receipts", "trips"].includes(role!)
          ? "custom"
          : role!,
        parentId: parent ? `${account.id}-${parent}` : null,
      });
  }
  const samples = [
    [
      "inbox",
      "Alex Rivera",
      "Coffee this weekend?",
      "Hi there,\n\nWould you like to meet at the corner café on Saturday at 10? I would love to catch up.\n\nAlex",
    ],
    [
      "inbox",
      "Community team",
      "Your September community digest",
      "This month: a neighborhood picnic, a book swap, and a new walking group. Reply to join us on Sunday.",
    ],
    [
      "inbox",
      "Northern Rail",
      "Your trip confirmation",
      "Your reservation is confirmed for October 3. Your sample ticket is attached. Please arrive 15 minutes before departure.",
    ],
    [
      "inbox",
      "Morgan Chen",
      "Photos from the garden",
      "Thanks for helping plant the garden yesterday. The sunflowers are looking great! Shall we plan another afternoon next month?",
    ],
    [
      "drafts",
      account.name,
      "Ideas for our next meetup",
      "Hi everyone,\n\nHere are a few ideas for our next meetup:\n• A short walk\n• Lunch in the park\n\nLet me know what you think.",
    ],
    [
      "sent",
      account.name,
      "Re: Saturday lunch",
      "Saturday at noon works for me. See you there!",
    ],
    [
      "archive",
      "Library team",
      "Your books have been renewed",
      "Your books are now due October 12. Thank you for visiting your local library.",
    ],
    [
      "receipts",
      "Corner Books",
      "Receipt for your book order",
      "Thank you for your order. One paperback: $18.00. This is a sample receipt.",
    ],
    [
      "trips",
      "Mountain Lodge",
      "Your October stay",
      "We look forward to welcoming you on October 3. Check-in begins at 3 PM. Your room includes breakfast.",
    ],
  ];
  const messages: Message[] = samples.map(
    ([folder, name, subject, body], i) => ({
      id: `${account.id}-sample-${i}`,
      accountId: account.id,
      folderId: `${account.id}-${folder}`,
      from: {
        name,
        address:
          folder === "sent" || folder === "drafts"
            ? account.address
            : `${name.split(" ")[0].toLowerCase()}@example.net`,
      },
      to: [
        folder === "sent" || folder === "drafts"
          ? "alex@example.net"
          : account.address,
      ],
      cc: [],
      subject,
      preview: body.replace(/\n/g, " "),
      bodyText: body,
      bodyHtml: i === 1 ? `<h1>${subject}</h1><p>${body}</p>` : null,
      receivedAt: `2026-09-${24 - i}T10:30:00Z`,
      isRead: i > 1,
      isNewSinceLastVisit: i === 0,
      isDraft: folder === "drafts",
      relatedGroupId: null,
      attachments:
        i === 2
          ? [
              {
                id: `${account.id}-sample-ticket`,
                filename: "ticket.pdf",
                mimeType: "application/pdf",
                sizeBytes: 2400,
                downloadState: "notDownloaded",
              },
            ]
          : [],
    }),
  );
  for (const [i, sample] of designSamples.entries()) {
    messages.push({
      ...messages[0],
      ...sample,
      id: `${account.id}-design-${i}`,
      from: { ...sample.from, name: sample.from.name || sample.from.address },
      to: sample.to || [account.address],
      cc: sample.cc || [],
      preview: sample.bodyText.replace(/\n/g, " "),
      receivedAt:
        sample.receivedAt ||
        `2026-09-23T${String(18 - i).padStart(2, "0")}:11:00Z`,
      isRead: i % 3 === 0,
      isNewSinceLastVisit: i === 2,
      attachments:
        i === 3
          ? [
              {
                id: `${account.id}-design-attachment`,
                filename: "ticket.pdf",
                mimeType: "application/pdf",
                sizeBytes: 2400,
                downloadState: "notDownloaded",
              },
            ]
          : [],
    });
  }
  return {
    ...state,
    folders,
    messages: [
      ...state.messages,
      ...messages.filter(
        (m) => !state.messages.some((existing) => existing.id === m.id),
      ),
    ],
  };
}

function upgradeDemoData(state: MailState): MailState {
  if (state.demoDataVersion === 3) return state;
  let next = { ...state, demoDataVersion: 3 };
  if (!next.accounts.some((a) => a.id === communityAccount.id))
    next = { ...next, accounts: [...next.accounts, communityAccount] };
  for (const account of next.accounts)
    next = { ...populateDemoMailbox(next, account), demoDataVersion: 3 };
  return next;
}

export function initialState(): MailState {
  const names = [
    "Roc Thunderbird",
    "Rhea Thunderbird",
    "Rochelle Thunderbird",
    "Design team",
    "Community news",
    "Rhea Thunderbird",
    "Skye",
    "Northern Rail",
    "Morgan Chen",
    "Rhea Thunderbird",
    "Roc Thunderbird",
    "Roc Thunderbird",
  ];
  const messages = fixture.messages.map((m, i) => ({
    ...m,
    from: {
      name: names[i],
      address: names[i].split(" ")[0].toLowerCase() + "@example.com",
    },
    attachments: m.attachments.map((a) => ({
      ...a,
      downloadState: a.downloadState as Attachment["downloadState"],
    })),
  })) as Message[];
  messages[0].subject = "One email workflow across mobile and desktop";
  messages[0].bodyText =
    "Rhea and friends,\n\nI’ve been thinking about how I actually use email: I triage on my phone and write deeper replies on desktop, but right now it feels like two different worlds instead of one ecosystem.\n\nI want to start something on my phone and finish it on desktop without friction, with drafts, stars, flags, labels and archive decisions all staying perfectly in sync.\n\nIf we keep the rules consistent across devices, email becomes a simple, reliable workflow instead of a reset every time I switch screens.\n\nWhat do you think?\n\nRoc";
  messages[0].preview =
    "I’ve been thinking about how I actually use email: I triage on my phone…";
  messages[0].isRead = false;
  messages[0].isNewSinceLastVisit = true;
  messages[0].receivedAt = "2026-09-24T11:11:00Z";
  messages[4].accountId = "personal";
  messages[4].folderId = "personal-inbox";
  messages[4].subject = "A little news from the Thunderbird community";
  messages[4].bodyHtml =
    "<h1>A little news, delivered.</h1><p>Welcome to your monthly update from the Thunderbird community.</p><h2>A calmer home for your email</h2><p>Small improvements add up. This month we’ve been working on better reading, clearer folders, and a more comfortable compose experience.</p><blockquote>Your inbox should work for you.</blockquote><p>Thank you for being part of an open, independent email community.</p><p>With appreciation,<br>The community team</p>";
  messages[4].bodyText =
    "A little news, delivered. Welcome to your monthly update from the Thunderbird community. A calmer home for your email. Small improvements add up. This month we’ve been working on better reading, clearer folders, and a more comfortable compose experience. Your inbox should work for you. Thank you for being part of an open, independent email community.";
  for (const message of messages)
    if (!message.isDraft)
      message.to = [
        message.accountId === "work" ? "rhea@example.org" : "rhea@example.com",
      ];
  const state: MailState = {
    accounts: [
      {
        id: "personal",
        name: "Rhea Thunderbird",
        address: "rhea@example.com",
        protocol: "imap",
        color: "blue",
      },
      {
        id: "work",
        name: "Rhea · Work",
        address: "rhea@example.org",
        protocol: "imap",
        color: "orange",
      },
    ],
    folders: fixture.folders.filter((f) => f.accountId !== "future"),
    messages,
    selectedFolder: "personal-inbox",
    features: { unified: false, threads: false },
    downloadPolicy: "onDemand",
    offline: false,
  };
  for (const a of state.accounts)
    for (const role of ["inbox", "drafts", "sent", "archive", "trash", "spam"])
      if (!state.folders.some((f) => f.accountId === a.id && f.role === role))
        state.folders.push({
          id: `${a.id}-${role}`,
          accountId: a.id,
          name: role[0].toUpperCase() + role.slice(1),
          parentId: null,
          role,
        });
  return upgradeDemoData(state);
}
export function filterMessages(
  state: MailState,
  {
    folderId,
    query = "",
    scope = "account",
    unread = false,
    attachments = false,
  }: {
    folderId: string;
    query?: string;
    scope?: string;
    unread?: boolean;
    attachments?: boolean;
  },
) {
  const account =
    state.folders.find((f) => f.id === folderId)?.accountId ??
    state.accounts[0]?.id;
  return state.messages
    .filter((m) => {
      const inLocation = query
        ? scope === "all" || m.accountId === account
        : folderId === "unified"
          ? state.folders.find((f) => f.id === m.folderId)?.role === "inbox"
          : m.folderId === folderId;
      const haystack = [
        m.from.name,
        m.from.address,
        ...m.to,
        m.subject,
        m.bodyText,
        ...m.attachments.map((a) => a.filename),
      ]
        .join(" ")
        .toLocaleLowerCase();
      return (
        inLocation &&
        (!unread || !m.isRead) &&
        (!attachments || m.attachments.length > 0) &&
        haystack.includes(query.trim().toLocaleLowerCase())
      );
    })
    .sort((a, b) => b.receivedAt.localeCompare(a.receivedAt));
}
export function moveMessage(
  state: MailState,
  id: string,
  role: string,
): MailState {
  const message = state.messages.find((m) => m.id === id);
  if (!message) return state;
  const folder = state.folders.find(
    (f) => f.accountId === message.accountId && f.role === role,
  );
  if (!folder) return state;
  return {
    ...state,
    messages: state.messages.map((m) =>
      m.id === id ? { ...m, folderId: folder.id } : m,
    ),
  };
}
export function dateLabel(value: string) {
  const date = new Date(value);
  if (value.slice(0, 10) === SNAPSHOT.slice(0, 10))
    return date.toLocaleTimeString("en-GB", {
      hour: "2-digit",
      minute: "2-digit",
      timeZone: "UTC",
    });
  return date.toLocaleDateString("en-US", {
    month: "short",
    day: "numeric",
    timeZone: "UTC",
  });
}
export function sizeLabel(bytes: number) {
  return bytes >= 1e6
    ? `${(bytes / 1e6).toFixed(1)} MB`
    : `${Math.round(bytes / 1000)} KB`;
}
export function readStored(): MailState {
  try {
    const parsed = JSON.parse(localStorage.getItem("kage-mail-v1") || "null");
    if (
      parsed &&
      Array.isArray(parsed.accounts) &&
      Array.isArray(parsed.messages) &&
      Array.isArray(parsed.folders) &&
      parsed.features
    )
      return upgradeDemoData(parsed);
  } catch {
    /* Start safely if storage is unavailable or stale. */
  }
  return initialState();
}
