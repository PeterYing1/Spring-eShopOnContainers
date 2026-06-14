const buyerId = "demo-user";
let catalogItems = [];
let basket = { buyerId, items: [] };
let currentPaymentOrder = null;

const money = value => Number(value || 0).toLocaleString(undefined, { style: "currency", currency: "USD" });

async function api(path, options = {}) {
  const response = await fetch(path, {
    ...options,
    headers: { "Content-Type": "application/json", "x-user-id": buyerId, ...(options.headers || {}) }
  });
  if (!response.ok && response.status !== 202) {
    throw new Error(await response.text());
  }
  if (response.status === 204) {
    return null;
  }
  const text = await response.text();
  return text ? JSON.parse(text) : null;
}

function setStatus(message) {
  document.querySelector("#status").textContent = message;
}

function showShop() {
  document.querySelector("#shop-view").hidden = false;
  document.querySelector("aside").hidden = false;
  document.querySelector("#payment-view").hidden = true;
  document.querySelector("#credit-card-view").hidden = true;
}

async function showPayment(orderNumber) {
  currentPaymentOrder = await api(`/api/v1/orders/${orderNumber}`);
  document.querySelector("#shop-view").hidden = true;
  document.querySelector("aside").hidden = true;
  document.querySelector("#payment-view").hidden = false;
  document.querySelector("#credit-card-view").hidden = true;
  document.querySelector("#payment-summary").innerHTML = `
    <div class="payment-card">
      <strong>Order #${currentPaymentOrder.ordernumber}</strong>
      <span class="muted">${currentPaymentOrder.status} - ${new Date(currentPaymentOrder.date).toLocaleString()}</span>
      <div class="payment-lines">
        ${currentPaymentOrder.orderitems.map(item => `
          <div class="payment-line">
            <span>${item.productname} x ${item.units}</span>
            <strong>${money(item.unitprice * item.units)}</strong>
          </div>
        `).join("")}
      </div>
      <div class="payment-total">
        <span>Total</span>
        <strong>${money(currentPaymentOrder.total)}</strong>
      </div>
    </div>
  `;
  document.querySelector("#pay-order").disabled = currentPaymentOrder.status === "paid";
  setStatus(currentPaymentOrder.status === "paid" ? "Payment complete" : "Ready for payment");
}

function showCreditCardPayment() {
  if (!currentPaymentOrder || currentPaymentOrder.status === "paid") return;
  document.querySelector("#shop-view").hidden = true;
  document.querySelector("aside").hidden = true;
  document.querySelector("#payment-view").hidden = true;
  document.querySelector("#credit-card-view").hidden = false;
  document.querySelector("#credit-card-summary").innerHTML = `
    <div class="payment-card">
      <strong>Order #${currentPaymentOrder.ordernumber}</strong>
      <span class="muted">Amount due</span>
      <div class="payment-total">
        <span>Total</span>
        <strong>${money(currentPaymentOrder.total)}</strong>
      </div>
    </div>
  `;
  setStatus("Enter card details");
}

async function loadCatalog() {
  const page = await api("/api/v1/catalog/items?pageSize=20&pageIndex=0");
  catalogItems = page.data;
  document.querySelector("#count").textContent = `${page.count} items`;
  document.querySelector("#catalog").innerHTML = catalogItems.map(item => `
    <article class="item">
      <img alt="${item.name}" src="${item.pictureUri}">
      <div class="item-body">
        <strong>${item.name}</strong>
        <span class="muted">${item.catalogBrand.brand} - ${item.catalogType.type} - ${item.availableStock} in stock</span>
        <span class="price">${money(item.price)}</span>
        <button data-add="${item.id}">Add to basket</button>
      </div>
    </article>
  `).join("");
}

async function loadBasket() {
  basket = await api(`/api/v1/basket/${buyerId}`);
  renderBasket();
}

function renderBasket() {
  const total = basket.items.reduce((sum, item) => sum + item.unitPrice * item.quantity, 0);
  document.querySelector("#basket-total").textContent = money(total);
  document.querySelector("#basket").innerHTML = basket.items.length ? basket.items.map(item => `
    <div class="basket-line">
      <strong>${item.productName}</strong>
      <span class="muted">${money(item.unitPrice)} each</span>
      <div class="line-actions">
        <button class="secondary" data-dec="${item.productId}">-</button>
        <span class="qty">${item.quantity}</span>
        <button class="secondary" data-inc="${item.productId}">+</button>
      </div>
    </div>
  `).join("") : `<p class="muted">Your basket is empty.</p>`;
}

async function saveBasket() {
  basket = await api("/api/v1/basket", { method: "POST", body: JSON.stringify(basket) });
  renderBasket();
}

async function addItem(id) {
  setStatus("");
  const item = catalogItems.find(product => product.id === id);
  const existing = basket.items.find(line => line.productId === id);
  if (existing) {
    existing.quantity += 1;
  } else {
    basket.items.push({
      id: crypto.randomUUID(),
      productId: item.id,
      productName: item.name,
      unitPrice: item.price,
      oldUnitPrice: 0,
      quantity: 1,
      pictureUrl: item.pictureUri
    });
  }
  await saveBasket();
  setStatus("Added to basket");
}

async function changeQuantity(id, delta) {
  setStatus("");
  const line = basket.items.find(item => item.productId === id);
  if (!line) return;
  line.quantity += delta;
  basket.items = basket.items.filter(item => item.quantity > 0);
  await saveBasket();
}

async function checkout() {
  try {
    if (!basket.items.length) {
      setStatus("Add an item first");
      return;
    }
    setStatus("Checking out...");
    const result = await api("/api/v1/basket/checkout", {
      method: "POST",
      headers: { "x-requestid": crypto.randomUUID() },
      body: JSON.stringify({
        city: "Redmond",
        street: "1 Microsoft Way",
        state: "WA",
        country: "USA",
        zipCode: "98052",
        cardNumber: "4012888888881881",
        cardHolderName: "Demo User",
        cardExpiration: "2028-12-31T00:00:00Z",
        cardSecurityNumber: "123",
        cardTypeId: 2,
        buyer: buyerId
      })
    });
    await loadBasket();
    await loadOrders();
    await showPayment(result.orderNumber);
  } catch (error) {
    setStatus("Checkout failed");
    console.error(error);
  }
}

async function submitCreditCardPayment(event) {
  event.preventDefault();
  try {
    if (!currentPaymentOrder) return;
    setStatus("Processing payment...");
    await api(`/api/v1/orders/${currentPaymentOrder.ordernumber}/pay`, { method: "PUT" });
    await loadOrders();
    showShop();
    setStatus("Payment complete");
  } catch (error) {
    setStatus("Payment failed");
    console.error(error);
  }
}

async function loadOrders() {
  const orders = await api("/api/v1/orders");
  document.querySelector("#orders").innerHTML = orders.length ? orders.map(order => `
    <div class="order-line">
      <strong>#${order.ordernumber} - ${order.status}</strong>
      <span class="muted">${new Date(order.date).toLocaleString()} - ${money(order.total)}</span>
    </div>
  `).join("") : `<p class="muted">No orders yet.</p>`;
}

document.addEventListener("click", event => {
  const add = event.target.dataset.add;
  const inc = event.target.dataset.inc;
  const dec = event.target.dataset.dec;
  if (add) addItem(Number(add));
  if (inc) changeQuantity(Number(inc), 1);
  if (dec) changeQuantity(Number(dec), -1);
});

document.querySelector("#checkout").addEventListener("click", checkout);
document.querySelector("#refresh-orders").addEventListener("click", loadOrders);
document.querySelector("#back-to-shop").addEventListener("click", showShop);
document.querySelector("#pay-order").addEventListener("click", showCreditCardPayment);
document.querySelector("#back-to-payment").addEventListener("click", () => showPayment(currentPaymentOrder.ordernumber));
document.querySelector("#credit-card-form").addEventListener("submit", submitCreditCardPayment);

Promise.all([loadCatalog(), loadBasket(), loadOrders()]).catch(error => {
  document.body.insertAdjacentHTML("beforeend", `<pre>${error.message}</pre>`);
});
