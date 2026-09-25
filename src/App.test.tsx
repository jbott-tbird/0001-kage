import { beforeEach, describe, expect, it } from "vitest";
import { render, screen, within, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import App from "./App";
import { initialState, filterMessages, moveMessage } from "./lib/mail";
beforeEach(() => {
  localStorage.clear();
  window.location.hash = "#/";
});
function start(path = "/mail") {
  window.location.hash = "#" + path;
  render(<App />);
  return userEvent.setup();
}
describe("mail prototype journeys", () => {
  it("completes manual setup and never persists the password", async () => {
    const user = start("/");
    await user.click(screen.getByRole("button", { name: "Get started" }));
    await user.click(
      screen.getByRole("button", { name: "Fill in demo credentials" }),
    );
    await user.click(screen.getByRole("button", { name: "Next" }));
    expect(screen.getByText("Planned for v2.0")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Next" }));
    await user.click(screen.getByRole("button", { name: "Save" }));
    expect(screen.getByText("You’re all set.")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Finish" }));
    expect(screen.getByText("skye@example.net")).toBeInTheDocument();
    const saved = localStorage.getItem("kage-mail-v1")!;
    expect(saved).not.toContain("sample-password");
    expect(JSON.parse(saved).accounts).toHaveLength(3);
  });
  it("opens a message, marks it read, and preserves its independent new state", async () => {
    const user = start();
    await user.click(
      screen.getByRole("button", { name: /One email workflow across mobile/ }),
    );
    expect(
      screen.getByRole("heading", {
        name: "One email workflow across mobile and desktop",
      }),
    ).toBeInTheDocument();
    await waitFor(() =>
      expect(
        JSON.parse(localStorage.getItem("kage-mail-v1")!).messages.find(
          (m: { id: string }) => m.id === "m01",
        ).isRead,
      ).toBe(true),
    );
    expect(
      JSON.parse(localStorage.getItem("kage-mail-v1")!).messages.find(
        (m: { id: string }) => m.id === "m01",
      ).isNewSinceLastVisit,
    ).toBe(true);
    await user.click(screen.getByRole("button", { name: "Archive message" }));
    expect(screen.getByRole("heading", { name: "Inbox" })).toBeInTheDocument();
    expect(
      screen.queryByRole("button", {
        name: /One email workflow across mobile/,
      }),
    ).not.toBeInTheDocument();
  });
  it("searches across accounts and within the selected account", async () => {
    const user = start();
    await user.click(screen.getByRole("button", { name: "Search messages" }));
    await user.type(
      screen.getByRole("textbox", { name: "Search mail" }),
      "Lighthouse",
    );
    expect(screen.getByText("2 results")).toBeInTheDocument();
    await user.selectOptions(
      screen.getByRole("combobox", { name: "Search scope" }),
      "all",
    );
    expect(screen.getByText("3 results")).toBeInTheDocument();
    expect(screen.getByText("Lighthouse review")).toBeInTheDocument();
  });
  it("switches mailboxes in the sliding drawer", async () => {
    const user = start();
    await user.click(
      screen.getByRole("button", { name: "Open account drawer" }),
    );
    const dialog = screen.getByRole("dialog");
    await user.click(within(dialog).getByRole("button", { name: "Trash" }));
    expect(screen.getByRole("heading", { name: "Trash" })).toBeInTheDocument();
    expect(screen.getByText("You’re all caught up")).toBeInTheDocument();
    expect(
      JSON.parse(localStorage.getItem("kage-mail-v1")!).selectedFolder,
    ).toBe("personal-trash");
  });
  it("saves a draft and restores its fields when reopened", async () => {
    const user = start("/compose");
    await user.type(
      screen.getByLabelText("To", { exact: true }),
      "friend@example.net",
    );
    await user.type(screen.getByLabelText("Subject"), "Weekend plans");
    await user.type(
      screen.getByRole("textbox", { name: "Message body" }),
      "Meet you on Saturday.",
    );
    await user.click(screen.getByRole("button", { name: "Save draft" }));
    await user.click(
      screen.getByRole("button", { name: "Open account drawer" }),
    );
    await user.click(
      within(screen.getByRole("dialog")).getByRole("button", {
        name: /^Drafts/,
      }),
    );
    await user.click(screen.getByRole("button", { name: /Weekend plans/ }));
    expect(screen.getByLabelText("To", { exact: true })).toHaveValue(
      "friend@example.net",
    );
    expect(screen.getByRole("textbox", { name: "Message body" })).toHaveValue(
      "Meet you on Saturday.",
    );
  });
  it("finds words inside a message", async () => {
    const user = start("/message/m01");
    await user.click(screen.getByRole("button", { name: "Find in message" }));
    await user.type(
      screen.getByRole("textbox", { name: "Find in this message" }),
      "desktop",
    );
    expect(screen.getByText("1 / 2")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Next match" }));
    expect(screen.getByText("2 / 2")).toBeInTheDocument();
  });
  it("blocks downloading uncached attachments while offline", async () => {
    const state = initialState();
    state.offline = true;
    localStorage.setItem("kage-mail-v1", JSON.stringify(state));
    const user = start("/message/m08");
    await user.click(screen.getByRole("button", { name: /ticket.pdf/ }));
    expect(screen.getByRole("status")).toHaveTextContent(
      "has not been downloaded",
    );
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });
  it("sends only into the local sample Sent folder", async () => {
    const user = start("/compose");
    await user.type(
      screen.getByLabelText("To", { exact: true }),
      "friend@example.net",
    );
    await user.type(screen.getByLabelText("Subject"), "Hello from the demo");
    await user.click(screen.getByRole("button", { name: "Send" }));
    expect(screen.getByRole("status")).toHaveTextContent("No email was sent");
    expect(
      JSON.parse(localStorage.getItem("kage-mail-v1")!).messages.find(
        (m: { subject: string }) => m.subject === "Hello from the demo",
      ).folderId,
    ).toBe("personal-sent");
  });
});
describe("mail model", () => {
  it("keeps archive actions within their original account", () => {
    const state = moveMessage(initialState(), "m04", "archive");
    expect(state.messages.find((m) => m.id === "m04")?.folderId).toBe(
      "work-archive",
    );
    expect(
      filterMessages(state, { folderId: "personal-inbox" }).every(
        (m) => m.accountId === "personal",
      ),
    ).toBe(true);
  });
});
