"use strict";

const byId = (id) => document.getElementById(id);
const text = (id, value) => {
    const element = byId(id);
    if (element) element.textContent = value;
};

function markCurrentNavigation() {
    const currentPage = document.body.dataset.page;
    document.querySelectorAll("[data-nav]").forEach((link) => {
        if (link.dataset.nav === currentPage) {
            link.setAttribute("aria-current", "page");
        }
    });
}

function initFormsPage() {
    const form = byId("profile-form");
    if (!form) return;

    const firstName = byId("first-name");
    const lastName = byId("last-name");
    const email = byId("email");
    const quantity = byId("quantity");
    const notes = byId("notes");
    const updates = byId("receive-updates");
    const accountType = byId("account-type");
    const pointerTarget = byId("interaction-target");

    const contactPreference = () =>
        document.querySelector('input[name="contact-preference"]:checked')?.value ?? "none";

    const updateState = () => {
        text("state-name", `Name: ${[firstName.value, lastName.value].filter(Boolean).join(" ") || "(empty)"}`);
        text("state-email", `Email: ${email.value || "(empty)"}`);
        text("state-quantity", `Quantity: ${quantity.value || "0"}`);
        text("state-notes", `Notes: ${notes.value || "(empty)"}`);
        text("state-updates", `Updates: ${updates.checked ? "checked" : "unchecked"}`);
        text("state-contact", `Contact Preference: ${contactPreference()}`);
        text("state-account", `Account Type: ${accountType.value || "none"}`);
    };

    form.addEventListener("input", updateState);
    form.addEventListener("change", updateState);

    form.addEventListener("submit", (event) => {
        event.preventDefault();
        text(
            "submitted-state",
            `Submitted: ${firstName.value || "(blank)"} | ${accountType.value || "none"} | ${contactPreference()} | ${updates.checked ? "updates" : "no updates"}`
        );
    });

    form.addEventListener("reset", () => {
        window.setTimeout(() => {
            updateState();
            text("submitted-state", "Submitted: not yet");
        }, 0);
    });

    pointerTarget.addEventListener("dblclick", () => text("pointer-state", "Last Pointer Action: double click"));
    pointerTarget.addEventListener("contextmenu", (event) => {
        event.preventDefault();
        text("pointer-state", "Last Pointer Action: right click");
    });
    pointerTarget.addEventListener("mouseenter", () => text("pointer-state", "Last Pointer Action: moved over"));

    updateState();
}

function initCatalogPage() {
    const search = byId("catalog-search");
    const category = byId("catalog-category");
    const cards = [...document.querySelectorAll(".product-card")];
    if (!search || !category || cards.length === 0) return;

    const filter = () => {
        const term = search.value.trim().toLowerCase();
        const selectedCategory = category.value;
        let visible = 0;

        cards.forEach((card) => {
            const matchesText = card.textContent.toLowerCase().includes(term);
            const matchesCategory = !selectedCategory || card.dataset.category === selectedCategory;
            card.hidden = !(matchesText && matchesCategory);
            if (!card.hidden) visible += 1;
        });

        text("catalog-result-count", `Visible Products: ${visible}`);
    };

    search.addEventListener("input", filter);
    category.addEventListener("change", filter);

    document.querySelectorAll(".details-button").forEach((button, index) => {
        button.addEventListener("click", () => text("selected-product", `Selected Product: ${index + 1}`));
    });

    document.querySelectorAll(".queue-action").forEach((button) => {
        button.addEventListener("click", () => text("queue-result", `Queue Result: ${button.dataset.queue}`));
    });

    const toggle = byId("advanced-filters");
    const panel = byId("advanced-filter-panel");
    toggle.addEventListener("click", () => {
        const expanded = toggle.getAttribute("aria-expanded") === "true";
        toggle.setAttribute("aria-expanded", String(!expanded));
        panel.hidden = expanded;
    });

    filter();
}

function initWorkflowPage() {
    const approval = byId("approval-check");
    const submit = byId("submit-request");
    const error = byId("validation-error");
    if (!approval || !submit || !error) return;

    const setState = (state) => {
        if (state === "ready") {
            approval.checked = true;
            error.hidden = true;
            submit.disabled = false;
        } else if (state === "error") {
            approval.checked = true;
            error.hidden = false;
            submit.disabled = true;
        } else {
            approval.checked = false;
            error.hidden = true;
            submit.disabled = true;
        }
        text("workflow-state", `Workflow State: ${state}`);
        text("request-result", "Request Result: none");
    };

    byId("use-ready-state").addEventListener("click", () => setState("ready"));
    byId("use-review-state").addEventListener("click", () => setState("review"));
    byId("use-error-state").addEventListener("click", () => setState("error"));
    byId("refresh-request").addEventListener("click", () => setState("review"));

    approval.addEventListener("change", () => {
        submit.disabled = !approval.checked || !error.hidden;
        text("workflow-state", `Workflow State: ${submit.disabled ? "review" : "ready"}`);
    });

    submit.addEventListener("click", () => text("request-result", "Request Result: submitted"));
    setState("review");
}

function initKeyboardPage() {
    const input = byId("keyboard-input");
    if (!input) return;

    const updateValue = () => text("keyboard-value", `Keyboard Value: ${input.value || "(empty)"}`);
    input.addEventListener("input", updateValue);
    input.addEventListener("keydown", (event) => text("last-key", `Last Key: ${event.key}`));
    updateValue();
}

function initDialogsPage() {
    const output = byId("dialog-result");
    if (!output) return;

    byId("show-alert").addEventListener("click", () => {
        window.alert("Pickleball alert");
        output.textContent = "Dialog Result: alert accepted";
    });

    byId("show-confirmation").addEventListener("click", () => {
        const accepted = window.confirm("Continue with this confirmation?");
        output.textContent = `Dialog Result: confirmation ${accepted ? "accepted" : "dismissed"}`;
    });
}

function initComponentsPage() {
    const form = byId("customer-form");
    if (!form) return;

    const name = byId("customer-name");
    const tier = byId("customer-tier");
    form.addEventListener("submit", (event) => {
        event.preventDefault();
        text("saved-customer", `Saved Customer: ${name.value || "(blank)"} | ${tier.value || "none"}`);
    });
    form.addEventListener("reset", () => window.setTimeout(() => text("saved-customer", "Saved Customer: none"), 0));
}

markCurrentNavigation();
initFormsPage();
initCatalogPage();
initWorkflowPage();
initKeyboardPage();
initDialogsPage();
initComponentsPage();
