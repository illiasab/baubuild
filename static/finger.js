async function mountFingers() {
  const nodes = document.querySelectorAll("[data-finger]");
  if (!nodes.length) return;
  if (!mountFingers.svg) {
    const response = await fetch("/tap-finger.svg");
    if (!response.ok) return;
    mountFingers.svg = await response.text();
  }
  nodes.forEach((node) => {
    if (node.querySelector(".finger")) return;
    const mark = document.createElement("span");
    mark.className = "finger";
    mark.setAttribute("aria-hidden", "true");
    mark.innerHTML = mountFingers.svg;
    node.appendChild(mark);
  });
}
