import { render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { ConfirmDialog } from "../components/dialogs";

describe("destructive confirmation focus", () => {
  it("focuses the safe cancel action instead of delete", async () => {
    const confirm = vi.fn();
    render(
      <ConfirmDialog
        open
        title="Delete this user?"
        confirmLabel="Delete"
        danger
        onClose={vi.fn()}
        onConfirm={confirm}
      />,
    );
    await waitFor(() => expect(screen.getByRole("button", { name: "Cancel" })).toHaveFocus());
    expect(confirm).not.toHaveBeenCalled();
  });
});
