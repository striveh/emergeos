(() => {
  const form = document.querySelector("#quick-capture-form");
  const linkField = document.querySelector("#link-source-field");
  const sourceRef = document.querySelector("#source-ref");
  const status = document.querySelector("#capture-status");
  const sourceChoices = document.querySelectorAll('input[name="sourceType"]');

  if (!form || !linkField || !sourceRef || !status || sourceChoices.length === 0) {
    return;
  }

  const syncSourceFields = () => {
    const selected = document.querySelector('input[name="sourceType"]:checked');
    const linkSelected = selected?.value === "LINK";
    linkField.hidden = !linkSelected;
    sourceRef.required = linkSelected;
  };

  sourceChoices.forEach((choice) => {
    choice.addEventListener("change", syncSourceFields);
  });

  form.addEventListener("submit", (event) => {
    event.preventDefault();
    status.textContent = "提交能力尚未启用；你的内容没有离开这个页面。";
  });

  syncSourceFields();
})();
