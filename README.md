# Product Management System + POS

A desktop inventory and point-of-sale system for a retail / wholesale shop.

**Built with:** Java 17, Swing with the FlatLaf look and feel, MVC architecture, MySQL through JDBC.

**What it covers:**
- Products with variants, bundles, batches with expiry dates, and serial numbers / IMEI.
- Stock in, stock out and adjustments, with a full movement history.
- A barcode-ready POS with multiple carts, split and mobile payments, dues, and hold / resume.
- Receipts (thermal and A4), PDF invoices and barcode labels.
- Returns, exchanges and cancellations.
- Cashier shifts and 15 reports.

---

## Contents
1. [Quick start](#1-quick-start)
2. [Logins, roles and permissions](#2-logins-roles-and-permissions)
3. [Navigation](#3-navigation)
4. [Screens and what every button does](#4-screens-and-what-every-button-does)
5. [Workflows (step by step)](#5-workflows-step-by-step)
6. [Business rules](#6-business-rules)
7. [Keyboard shortcuts](#7-keyboard-shortcuts)
8. [Feature checklist](#8-feature-checklist)
9. [Database](#9-database)
10. [Project structure (MVC)](#10-project-structure-mvc)
11. [Troubleshooting](#11-troubleshooting)

---

## 1. Quick start

**Requirements**
- JDK 17 or newer.
- MySQL / MariaDB running on `localhost:3306`, user `root`, empty password. XAMPP works: start MySQL in the XAMPP Control Panel.
- Database name: `ProductMSEasirArafat`.

**Create the database** (pick one)
- Just start the app. If the database is missing, it offers to create it from `db/schema.sql` and `db/seed.sql`.
- Or load it manually in PowerShell:
  ```powershell
  Get-Content db\schema.sql, db\seed.sql | C:\xampp\mysql\bin\mysql.exe -uroot
  ```
  `schema.sql` **drops and recreates** the database. Use it to reset to clean demo data.

**Run**
| Where | How |
|---|---|
| NetBeans | File → Open Project → select this folder (Maven project) → Run (F6). `nbactions.xml` sets the main class and working folder. |
| Without an IDE | Double-click `run.bat`. It compiles with the jars in `lib\` and starts the app. |
| Maven | `mvn package` builds `target/ProductMSbyEasirArafat-2.0.jar`, with its libraries copied to `target/lib`. Run `java -jar target/ProductMSbyEasirArafat-2.0.jar` from the project folder. |

**Other connection details:** create `db.properties` in the project folder with any of these keys: `host`, `port`, `user`, `password`, `database`.

**Demo data**
- 2 users.
- 13 categories, including subcategories.
- 9 brands, 5 units, 3 suppliers, 3 customers.
- 11 products / 17 variants. These include:
  - batch-tracked milk with one **expired** batch and one **expiring** batch,
  - an IMEI-tracked smartphone,
  - a Mouse + Keyboard **combo**,
  - an **out-of-stock** notebook,
  - several **low-stock** items.

---

## 2. Logins, roles and permissions

| Username | Password | Role |
|---|---|---|
| `admin` | `admin123` | ADMIN |
| `cashier` | `cashier123` | CASHIER |

An admin can create more users, including MANAGER accounts, in **Settings → Users & Roles**.

| Module / action | CASHIER | MANAGER | ADMIN |
|---|:-:|:-:|:-:|
| Dashboard, POS, Sales, Customers, Shifts | ✔ | ✔ | ✔ |
| Products, Stock, Reports, Catalog Setup | – | ✔ | ✔ |
| Settings (shop, tax, POS rules, users) | – | – | ✔ |
| Cancel a sale | – | ✔ | ✔ |
| Discounts above the cashier limit (default 10%) | needs manager password | ✔ | ✔ |
| Returns older than the return period (default 7 days) | – | ✔ | ✔ |
| Close another user's shift | – | ✔ | ✔ |

**Login screen**
- Username, password (with a show / hide eye), **Sign in** (Enter).
- After 5 wrong attempts, login is locked until the app is restarted.

**After login**
- Admins and managers land on the **Dashboard**.
- Cashiers land on the **POS**. If no shift is open, they are reminded and taken to **Shifts**.

---

## 3. Navigation

```
┌───────────────┬──────────────────────────────────────────────────────────────┐
│ ■ ProductMS   │  Page title     ● Shift #3 open   Sat, 07 Nov 2026 08:15 PM  │
│ Shop name     │                                     [Alerts (6)] [Dark mode]  │
│               ├──────────────────────────────────────────────────────────────┤
│ ▪ Dashboard   │                                                              │
│ ► POS         │                                                              │
│ ■ Products    │                 module content                               │
│ ≡ Stock       │                                                              │
│ ◊ Sales       │                                                              │
│ ● Customers   │                                                              │
│ ○ Shifts      │                                                              │
│ ▲ Reports     │                                                              │
│ ♦ Catalog     │                                                              │
│ § Settings    │                                                              │
│               │                                                              │
│ User / role   │                                                              │
│ [Log out]     │                                                              │
└───────────────┴──────────────────────────────────────────────────────────────┘
```

- **Sidebar:** click a module, or press **Ctrl + its number**. Numbers follow the order shown to *you*:

  | Role | Ctrl+1 | Ctrl+2 | Ctrl+3 | Ctrl+4 | Ctrl+5 | Ctrl+6 | Ctrl+7 | Ctrl+8 | Ctrl+9 | Ctrl+0 |
  |---|---|---|---|---|---|---|---|---|---|---|
  | Admin | Dashboard | POS | Products | Stock | Sales | Customers | Shifts | Reports | Catalog Setup | Settings |
  | Manager | Dashboard | POS | Products | Stock | Sales | Customers | Shifts | Reports | Catalog Setup | – |
  | Cashier | Dashboard | POS | Sales | Customers | Shifts | – | – | – | – | – |

- **Top bar:**
  - Shift status: green when a shift is open, orange when none is open.
  - Live clock.
  - **Alerts (n):** opens a list of out-of-stock, low-stock, expiring and expired items. The count refreshes every 2 minutes and after every stock change.
  - **Dark mode / Light mode:** switches the theme and remembers your choice.
- **Log out** (bottom of the sidebar): warns you if any POS cart still has items.
- **Closing the window** asks for confirmation.
- Every screen reloads its data each time you open it.

---

## 4. Screens and what every button does

### 4.1 Dashboard
| Area | Content |
|---|---|
| KPI cards | Today's Sales, Today's Gross Profit, Invoices Today (hover to see today's returns), This Month, Stock Value at cost, Customer Dues |
| Alert cards | Low Stock, Out of Stock, Expiring Soon, Expired Batches. Click any of them to open the Alerts list. |
| Sales – last 7 days | Bar chart; today's bar is highlighted |
| Top products (30 days) | Product, quantity, revenue |
| Recent sales | Last 12 invoices with payment status and sale status |
| Stock & expiry alerts | Every item needing attention, sorted by urgency |
| Buttons | **Refresh**, **+ New Sale** (opens the POS) |

### 4.2 POS (Point of Sale)
**Left side: find products**
- **Search / scan box (F1).** Type to filter the tiles. Press Enter to add:
  - an exact barcode or SKU adds that item;
  - `3*2000000000015` adds 3 of that item;
  - an IMEI / serial adds that exact unit;
  - if only one tile matches the text, it is added.

  **Esc** clears the box; **↓** jumps to the cart.
- **Category filter:** a category also shows its subcategories.
- **Product tiles:** name, variant count or variant, price, and available stock. The bottom stripe is green (in stock), orange (low) or red (out). Click to add; a product with several variants opens a variant picker. Up to 120 tiles are shown.
- **Footer:** number of matching products, and a cheat sheet of shortcuts.

**Right side: cart**
| Control | What it does |
|---|---|
| Cart tabs, **+ New cart (F8)** | Up to 8 carts open at once (e.g. one customer is still shopping while you serve the next). Each tab shows its line count. |
| Customer button **(F4)** | Search customers by name or phone, or **+ New customer** right there. Choosing a wholesale customer switches the cart to wholesale prices. Shows the customer's phone, type and previous due. |
| **Wholesale prices (F7)** | Toggles retail / wholesale pricing for the whole cart |
| Cart table | Item (variant, serials, "[wholesale qty]" marker), quantity with unit, unit price, discount, line total |
| **−** / **+** (Ctrl+↓ / Ctrl+↑, numpad −/+) | Lower or raise quantity by 1. For serial items, + opens the serial picker and − removes the last serial. |
| **Qty F2** | Number pad for any quantity or weight. Serial items reopen the serial picker. |
| **Disc F3** | Item discount, as an amount or a percent |
| **Remove** (Del) | Removes the selected line |
| Totals | Line and item count, Subtotal, Discount, VAT (or "VAT (included)"), big **TOTAL**. With store credit, it shows `total → amount to pay`. |
| **Hold (F5)** | Saves the cart under a name to the database and closes the tab |
| **Held carts (F6)** | Resume or discard held carts. If stock changed meanwhile, you get a warning. |
| **Cart disc. (F10)** | Discount on the whole cart, as an amount or a percent |
| **Void cart** | Clears and closes the cart, after confirmation |
| **Last receipt (Ctrl+P)** | Reprints your most recent sale |
| **PAY (F9)** | Opens the payment screen. If no shift is open, it first asks for the opening cash and opens one. |

**Payment screen**
- **Left:** customer, invoice total, store credit, **amount to pay**, paid, remaining / due, **change to return**, and the list of payment lines (**Remove payment line**).
- **Right:**
  - Method buttons: **Cash, Card, bKash, Nagad, Rocket**.
  - Amount box, pre-filled with what remains.
  - Reference field: card last 4 digits / approval code, or the TrxID for mobile banking. Required for everything except cash.
  - Quick-cash buttons for cash: **Exact**, plus the next round notes (e.g. 5,020 / 5,050 / 5,100 / 5,500 / 6,000).
  - **Add payment (Enter)**.
  - Receipt choice: Thermal 80 mm / Thermal 58 mm / A4 invoice / No receipt.
- **Bottom:** **Back to cart (Esc)**, **Complete sale (F9 / Ctrl+Enter)**. Pressing Enter on an empty amount once fully paid also completes the sale.

**Receipt preview (after a sale or a reprint)**
- The headline shows the invoice number, change and due.
- Switch between **58 mm / 80 mm / A4 invoice**.
- **Print (Enter)**, **Save PDF** (offers to open the file), **Close (Esc)**.

### 4.3 Products
| Control | What it does |
|---|---|
| Search | Name, SKU, barcode, brand, size, colour, weight (Ctrl+F) |
| Filters | Category (includes subcategories), Brand, Status (All / Active / Inactive), Stock (All / In / Low / Out) |
| **+ New Product** (Ctrl+N) / **Edit** / double-click a row | Opens the product editor |
| **Deactivate / Activate** | Hides or shows the product (all its variants) in the POS; history is kept |
| **Delete** | Permanent. Blocked if the product has sales or is part of a combo. |
| **Stock In** | Opens Stock → Stock In with the selected item ready |
| **Print Labels** | Barcode label printing for the selected rows (multi-select with Ctrl / Shift) |
| **Import CSV / Export CSV / CSV Template** | Bulk create or update; export everything |
| Table | SKU, Product, Variant, Category, Brand, Unit, Cost, Retail, Wholesale, Stock, Available, Status (IN STOCK / LOW STOCK / OUT OF STOCK / INACTIVE) |
| Footer | Item count and stock value at cost |
| Detail panel | Image, full details (type, tracking, prices, margin %, wholesale threshold, VAT, discount, stock, available, reorder level, description), barcode image and QR code |

**Product editor**
- **Save product** (Ctrl+S), **Cancel** (Esc).
- If retail is below cost, it asks before saving.

**General tab**
- Name, Category / subcategory, Brand, Unit.
- Product type: Standard or Bundle / Combo.
- VAT %.
- Product discount: none / percent / fixed amount per unit.
- Tracking: batch & expiry, *or* serial / IMEI (not both).
- Active.
- Description.
- Product image: **Choose image…**, **Remove**.

**Variants, SKU & Prices tab** (editable grid)
- Columns: Size, Colour, Weight, SKU, Barcode, Cost, Retail\*, Wholesale, Wholesale-from qty, Reorder level, Opening stock (new rows only), Active.
- **+ Add variant** copies the first row's prices.
- **Remove variant**: a variant with sales or stock is deactivated instead of deleted.
- **Generate size × colour…** creates every combination, e.g. S, M, L, XL × Black, White = 8 variants.
- **Copy first row prices to all**.
- Leave SKU / barcode blank to generate them. SKU looks like `COTCRE-00021`; the barcode is a valid internal EAN-13 starting `200…`.

**Bundle components tab**
- **+ Add component**, **Change qty**, **Remove**.
- Shows the separate price, the combo price and the customer's saving.

### 4.4 Stock
| Tab | Controls |
|---|---|
| **Stock In (receive goods)** | Scan / SKU box or **Find product…**. Quantity, unit cost (shows the total purchase value), supplier, reference number (supplier invoice / GRN), note. Batch-tracked items also need batch / lot number, manufactured date, expiry date. Serial-tracked items need one serial / IMEI per line (scanner friendly). Then **Receive stock**. The right side lists goods received in the last 30 days. |
| **Adjust / Damaged / Expired** | Choose the product. Stock count helper: enter the **physical count** → **Set from physical count** fills in the type, quantity and reason. Type: Adjustment OUT / Adjustment IN / Damaged / Expired. Quantity, batch (batch items), serials (serial items), **reason (required)**. Then **Apply adjustment** (asks to confirm). |
| **Movement History** | From / To dates, type (Opening, Stock in, Sale, Sale cancel, Return, Adjustment in / out, Damaged, Expired), search by product / SKU / reference, **Search**, **Export CSV**. Shows date, product, quantity (+/−), running balance, unit cost, value, batch, reference, supplier, note and user. |
| **Batches & Expiry** | Filter: all batches with stock / expiring soon / expired. Search, **Write off selected batch** (recorded as EXPIRED if past its date, otherwise DAMAGED). Columns include days left and a status (OK / EXPIRING / EXPIRED). |
| **Serial Numbers** | Search a serial / IMEI or product to see its status (IN_STOCK / SOLD / DAMAGED), invoice, sale date and customer. Useful for warranty checks. |

### 4.5 Sales
**Invoices tab**
- **Filters:** From / To dates, search (invoice, customer name, phone), status (All / Completed / Cancelled), payment (All / Paid / Partial / Due), **Search**, **Today**, **Export CSV**.
- **Invoice table:** invoice, date, customer, cashier, lines, total, paid, due, returned, payment status, status.
- **Footer:** number of completed invoices, sales total, outstanding due.
- **Details:** items (qty, price, discount, VAT, total, returned, serials) and payments (method, type: sale or due collection, amount, reference, time).
- **Actions:**

  | Button | What it does |
  |---|---|
  | **View / Reprint** (or double-click) | Receipt preview: 58 / 80 mm / A4 / PDF, marked "REPRINT" |
  | **Return / Refund** | Return items for money |
  | **Exchange** | Return items for store credit |
  | **Collect Due** | Shown when the invoice has a due |
  | **Cancel Sale** | Managers only; disabled once any item was returned |

**Returns, Refunds & Cancellations tab**
- Date range and search.
- Shows return number (`RET-…`, or `CXL-…` for cancellations), invoice, customer, type, goods value, amount refunded, method, reason and user.

**Return dialog**
- Per line: sold, already returned, returnable, **return qty**, **condition** (GOOD / DAMAGED), refund value.
- **Return everything**.
- **Refund money** (choose Cash / Card / bKash / Nagad / Rocket) or **Exchange** (store credit).
- **Reason (required)**.
- Serial items then ask which serials are coming back.

### 4.6 Customers
- **Find:** search by name or phone; tick **Only customers with due**.
- **Edit:** **+ New Customer**, **Edit** (or double-click). Fields: name, phone (unique), email, address, type (Retail / Wholesale), active. The walk-in customer is fixed and can't be edited.
- **Collect Due:** amount, method, reference. Payment goes to the oldest due invoices first.
- **Purchase History:** every invoice of the customer with totals, paid, due and returned.
- **Export CSV**.

### 4.7 Shifts
**Current shift card**
- Status, opened time, cashier and opening cash.
- Live summary:
  - opening cash, invoices, gross sales, discounts, VAT, sales on due;
  - received by method: Cash, Card, bKash, Nagad, Rocket, Store credit;
  - due collected;
  - cash refunds and other refunds;
  - **expected cash in the drawer**.
- **Open Shift:** number pad for the opening cash.
- **Close Shift:** count the cash. It shows expected, counted, and SHORT or OVER. A note is required when there is a difference. It then offers to print the Z report.
- **Print X report (mid-shift)**, **Refresh**.

**Shift history**
- Every shift with expected, counted, over / short, invoices and sales.
- **Print selected shift report**.

### 4.8 Reports
- **Left:** list of 15 reports.
- **Top:** From / To dates; **Today / 7 days / This month / This year**; **Run report**; **Export CSV**; **Print** (table printout with the title and date range).
- **Bottom:** row count and totals of the summable columns.
- Reports marked "(current)" ignore dates and show stock as it is now.

| Report | Shows |
|---|---|
| Daily Sales Summary | Per day: invoices, gross, discounts, VAT, net sales, returns, cost, gross profit, collected, due |
| Product-wise Sales | Per variant: qty sold / returned, revenue, returned value, cost, profit net of returns |
| Sales Profit by Invoice | Total, VAT, cost, profit and margin % per invoice |
| Product Profit Margins *(current)* | Cost vs retail and wholesale, profit and margin % |
| Inventory & Stock Valuation *(current)* | Stock, value at cost, value at retail, potential profit |
| Low Stock *(current)* | Stock vs reorder level, suggested order quantity |
| Out of Stock *(current)* | Items at zero, with the last time stock went out |
| Expiry Report *(current)* | Batches expired or expiring within the alert window, with days left and value |
| Damaged & Expired Products | Every write-off with quantity, loss value, batch and reason |
| Stock Movement Summary | Per variant: received, sold, returned, adjusted, written off, net change, current stock |
| Payment Methods | Amount and transactions per method and type (sale / due collection) |
| Sales by Cashier | Invoices, sales, average basket, discounts, gross profit |
| Sales by Category | Quantity, revenue, gross profit per top-level category |
| Customer Dues *(current)* | Customers owing money, number of due invoices, oldest due date |
| Sales Returns | All returns, refunds and cancellations |

### 4.9 Catalog Setup
Four tabs, each with **+ Add / Edit / Delete**:
- **Categories & Subcategories:** name, parent (top-level or under a category), description, active. Subcategories are shown indented under their parent.
- **Brands:** name, active.
- **Units:** name, short name, "allow fractional quantities" (e.g. KG, Litre). Demo units: PCS, KG, L, BOX, PACK.
- **Suppliers:** name, phone, address, active.

You can't delete anything that is still in use. Deactivate it instead.

### 4.10 Settings (Admin)
**General tab, Shop details** (printed on receipts and invoices)
- Shop name, address, phone, VAT / BIN number, currency symbol, receipt footer.

**General tab, Rules**
- Prices include VAT (VAT is extracted) vs prices exclude VAT (VAT is added).
- Default receipt: 80 / 58 / A4.
- Cashiers must open a shift before selling.
- Cashier max discount %.
- Return period in days.
- Expiry alert days.

**Users & Roles tab**
- **+ Add user**, **Edit**: username, full name, role, password, active.
- **Reset password**, **Change my password**.
- There must always be at least one active admin, and you can't deactivate yourself.

### 4.11 Barcode label printing (Products → Print Labels)
- **Item list:** product, barcode, editable **copies** (defaults to the current stock, 1–100). **+ Add product**, **Remove**.
- **Label stock:**
  - A4 sheet 38 × 21 mm (65 per sheet)
  - A4 sheet 64 × 34 mm (24 per sheet)
  - Roll 50 × 25 mm
  - Roll 40 × 30 mm
- **Options:** shop name, price, QR code, use wholesale price.
- **Preview** shows the first sheet / label.
- **Print…** opens the Windows print dialog.
- Barcodes print as EAN-13 when valid, otherwise as Code-128.

---

## 5. Workflows (step by step)

### 5.1 First-time setup (Admin)
1. **Settings → General:** enter the shop details and choose your VAT style, receipt size and rules → **Save settings**.
2. **Settings → Users & Roles:** add managers and cashiers, then change the demo passwords.
3. **Catalog Setup:** add your categories / subcategories, brands, units and suppliers.
4. **Products → + New Product** for each product (or use **CSV Template** → fill it in Excel → **Import CSV**).
5. **Stock → Stock In** to receive the opening stock (needed for batch and serial items).
6. **Products → Print Labels** for items without a manufacturer barcode.

### 5.2 Add a product with sizes and colours
1. **Products → + New Product**.
2. General tab: name, category, brand, unit, VAT, any product discount.
3. Variants tab: fill in the first row's cost / retail / wholesale / wholesale-from qty / reorder level.
4. **Generate size × colour…** → type `S, M, L, XL` and `Black, White`. This creates 8 variants with the same prices.
5. Change any price that differs. Enter opening stock for new rows if the product is not batch / serial tracked.
6. **Save product.** Blank SKUs and barcodes are generated.

### 5.3 Create a combo / bundle
1. **+ New Product** → Product type **Bundle / Combo**.
2. Variants tab: set the combo's retail (and wholesale) price.
3. Bundle components tab: **+ Add component** for each item, then **Change qty** if needed (at least 2 components). The tab shows the customer's saving.
4. Save. A combo has no stock of its own: available = what its components allow. Selling it deducts each component.

### 5.4 Receive goods (stock in)
1. **Stock → Stock In**, then scan the barcode (or **Find product…**).
2. Enter the quantity and unit cost; choose the supplier and enter their invoice number.
3. **Batch-tracked:** enter the batch number and expiry date (manufacture date is optional). A past expiry date is rejected.
4. **Serial-tracked:** scan one IMEI per line. The count must equal the quantity, and duplicates are rejected.
5. **Receive stock.** Stock increases, the average cost is recalculated, and the movement is logged.

### 5.5 Stock count / correction
1. **Stock → Adjust**, choose the product, type the **physical count** → **Set from physical count**.
2. Check the type (OUT / IN), quantity and reason → **Apply adjustment** → confirm.

### 5.6 Damaged or expired goods
- **Single items:** Stock → Adjust → type **Damaged** or **Expired** → quantity (batch / serials if tracked) → reason → Apply.
- **Whole expired batch:** Stock → Batches & Expiry → filter **Expired** → select the batch → **Write off selected batch**.
- The loss appears in **Reports → Damaged & Expired Products**.

### 5.7 A normal sale (cash)
1. *(Cashier)* **Shifts → Open Shift** and enter the opening cash. You can also skip this: PAY asks for the opening cash the first time.
2. **POS:** scan items, or tap tiles. Weighed items ask for the weight; phones ask for the IMEI.
3. **F9 PAY.** Type the cash received (or tap a quick-cash button such as **1,000**). The screen shows the change.
4. **Complete sale (F9).** The receipt preview opens → **Print (Enter)**. Stock is deducted immediately.

### 5.8 Split payment (e.g. bKash + cash)
1. **F9 PAY** → **bKash** → type 3,000 → enter the TrxID → **Add payment**.
2. **Cash** → the remaining amount is pre-filled. Type what the customer hands over → **Add payment**.
3. **Complete sale.** Both payments appear on the receipt with their references.

### 5.9 Credit / due sale for a registered customer
1. In the cart, press **F4** → pick (or **+ New customer**) the customer.
2. **F9 PAY** → add a partial payment, or none.
3. **Complete sale** → confirm "Record X as DUE". The receipt shows **INVOICE (DUE)**.
4. Later: **Customers → Collect Due** (or **Sales → Collect Due**). Payment is applied to the oldest invoices first.

### 5.10 Wholesale sale
- Pick a **Wholesale** customer: the cart switches to wholesale prices automatically. Or toggle **F7**.
- In retail mode, a line also gets the wholesale price automatically once its quantity reaches the item's *wholesale-from* quantity. The cart marks it "[wholesale qty]".

### 5.11 Discounts
- **Item discount:** select the line → **F3** → choose Amount or Percent.
- **Cart discount:** **F10**. It is spread over the lines in proportion to their value, so VAT stays correct.
- **Over the limit:** if a cashier's discount is above the limit (Settings, default 10%), a manager types their username and password to approve it.
- **Product discounts** set in the product editor apply automatically.

### 5.12 Several customers at once / hold and resume
- **F8** opens another cart tab; switch between tabs freely.
- **F5 Hold** saves the cart under a name, such as the customer's name. It survives a restart.
- **F6 Held carts** → **Resume**. If stock changed in the meantime, you are warned.

### 5.13 Customer return (refund)
1. **Sales** → find the invoice (search by invoice number or phone) → **Return / Refund**.
2. Enter the return quantity per line and the condition (**GOOD** goes back to stock in the same batch; **DAMAGED** is written off). Choose the refund method and enter the reason.
3. For serial items, pick which serials are coming back.
4. **Process return.** Any due on the invoice is reduced first; the rest is refunded.

### 5.14 Exchange (e.g. wrong size)
1. **Sales** → invoice → **Exchange** → choose the items and reason → Process.
2. The POS opens a new cart, **"Exchange RET-…"**, carrying the store credit.
3. Add the replacement items → **F9**:
   - if the new items cost more, the customer pays the difference;
   - if they cost less, the difference is returned as cash change.

### 5.15 Cancel a sale (manager)
**Sales** → invoice → **Cancel Sale** → reason and refund method. All stock, batches and serials are restored. The invoice is marked CANCELLED and logged as `CXL-…`.

### 5.16 Reprint a receipt
- The last sale: **Ctrl+P** in the POS.
- Any invoice: **Sales** → double-click it → choose 58 mm / 80 mm / A4 → **Print**, or **Save PDF**.

### 5.17 End of day
1. **Shifts → Close Shift:** count the cash and enter it. It shows expected vs counted (over / short); add a note if they differ.
2. Print the **Z report**.
3. **Reports → Daily Sales Summary / Payment Methods / Sales by Cashier** → **Today** → **Export CSV** or **Print**.

### 5.18 Bulk import / export
- **Export CSV** exports every product variant (opens in Excel).
- **CSV Template** → fill it in → **Import CSV**:
  - rows with the same product name + category + subcategory become variants of one product;
  - an existing SKU is **updated**;
  - a new or blank SKU is **created**;
  - categories, brands and units that don't exist yet are created;
  - errors are listed with their line numbers.
- Columns:
  `sku, barcode, product_name, description, category, subcategory, brand, unit, size, colour, weight, purchase_price, retail_price, wholesale_price, wholesale_min_qty, vat_percent, discount_type, discount_value, reorder_level, opening_stock, active`

---

## 6. Business rules

**Stock**
- No sale, adjustment or write-off can take stock below zero.
- Every change is recorded in the movement ledger with the user, time, reference and reason.

**Batches**
- Expired batches are never sold.
- Sales take stock from the batch that expires first (FEFO).
- Returns go back to the batch the item came from.

**Serials**
- Each sold unit is linked to its invoice, so a serial / IMEI can't be sold twice.
- A returned serial goes back to IN_STOCK, or to DAMAGED if returned damaged.

**Combos**
- Availability = the minimum of (component stock ÷ quantity per combo).
- Selling a combo deducts its components; returning it restores them.

**Cost**
- The purchase price is a **weighted average**, updated on every stock in.
- Profit uses the actual cost of the batches sold.

**Price calculation, per line**
1. Unit price: retail, or wholesale when wholesale mode is on or the wholesale-from quantity is reached.
2. − product discount
3. − item discount
4. − share of the cart discount
5. VAT: added on top, or extracted from the price (depending on the setting).

**Payments**
- Card and mobile payments can't exceed what is owed; only cash produces change.
- Part payment or due requires a registered customer (not walk-in).

**Returns**
- Allowed within the return period; managers can override it.
- You can't return more than was sold minus what was already returned.

**Cancellation**
- Managers only, and only if nothing has been returned from the invoice.

**Shift cash**
- Expected cash = opening cash + cash received − cash refunds.

**Invoice numbers**
- `INV-yyMMdd-00001`
- Returns: `RET-…`; cancellations: `CXL-…`

---

## 7. Keyboard shortcuts

| Where | Key | Action |
|---|---|---|
| Anywhere | Ctrl+1 … Ctrl+0 | Switch module |
| POS | F1 | Focus search / scan |
| POS | F2 | Change quantity / serials |
| POS | F3 | Item discount |
| POS | F4 | Choose customer |
| POS | F5 | Hold cart |
| POS | F6 | Held carts |
| POS | F7 | Toggle wholesale prices |
| POS | F8 | New cart |
| POS | F9 | Pay |
| POS | F10 | Cart discount |
| POS | Ctrl+↑ / Ctrl+↓ | Quantity +1 / −1 |
| POS | Ctrl+P | Reprint last receipt |
| POS search box | Enter / Esc / ↓ | Add item / clear / jump to cart |
| POS cart table | Del, numpad + / − | Remove line, change quantity |
| Payment | Enter | Add payment (or complete when fully paid) |
| Payment | F9 or Ctrl+Enter | Complete sale |
| Payment | Esc | Back to cart |
| Receipt preview | Enter / Esc | Print / close |
| Products | Ctrl+N / Ctrl+F | New product / search |
| Product editor | Ctrl+S / Esc | Save / cancel |
| Pickers and dialogs | Enter / Esc | Select / close |

Touch screens: the product tiles, variant buttons, number pads and payment buttons are all large enough to tap.

---

## 8. Feature checklist

### Product Management
| # | Feature | Where |
|---|---|---|
| 1 | Add, edit, delete products | Products → New / Edit / Delete |
| 2 | Categories and subcategories | Catalog Setup → Categories; product editor |
| 3 | Brands | Catalog Setup → Brands |
| 4 | Variants (size, colour, weight) | Product editor → Variants (+ size × colour generator) |
| 5 | SKU management | Unique per variant; auto-generated or manual |
| 6 | Barcode and QR generation | Auto EAN-13; barcode and QR in the Products detail panel |
| 7 | Barcode label printing | Products → Print Labels |
| 8 | Images and descriptions | Product editor → General |
| 9 | Units (PCS, KG, Litre, Box) | Catalog Setup → Units (fractional quantities optional) |
| 10 | Purchase and selling prices | Variant cost / retail |
| 11 | Wholesale and retail pricing | Variant wholesale price and wholesale-from quantity |
| 12 | Product-wise discounts | Product editor: percent or fixed per unit |
| 13 | Stock management | Products, Stock |
| 14 | Stock in and stock out | Stock → Stock In; Adjust (OUT) |
| 15 | Stock adjustments | Stock → Adjust (with physical count helper) |
| 16 | Low stock alerts | Dashboard, Alerts button, Low Stock report |
| 17 | Out-of-stock alerts | Dashboard, Alerts button, tiles, report |
| 18 | Expiry date tracking | Batches; Batches & Expiry tab; Expiry report |
| 19 | Batch and serial tracking | Product editor tracking options; Stock In; Serial Numbers tab |
| 20 | Damaged and expired products | Adjust → Damaged / Expired; batch write-off; damaged returns; report |
| 21 | Returns and restocking | Sales → Return (good items go back to the same batch) |
| 22 | Bundles and combos | Product type Bundle / Combo |
| 23 | Stock movement history | Stock → Movement History |
| 24 | Stock valuation | Inventory & Stock Valuation report; Dashboard |
| 25 | Search and filtering | Products search and 4 filters |
| 26 | Bulk import and export | Products → Import / Export CSV, CSV Template |
| 27 | Activation / deactivation | Products → Deactivate / Activate; variant Active column |
| 28 | Inventory reports | Reports (valuation, low, out, expiry, movement, damaged) |
| 29 | Product-wise profit margins | Product Profit Margins report; Products detail panel |
| 30 | Automatic stock deduction after sales | Checkout (including combos and FEFO batches) |

### Point of Sale
| # | Feature | Where |
|---|---|---|
| 1 | POS dashboard | POS screen and Dashboard |
| 2 | Fast product search | POS search box and tiles |
| 3 | Barcode scanner integration | Scan + Enter, `qty*code`, IMEI scan |
| 4 | Product selection and cart | Tiles and cart table |
| 5 | Add, update, remove cart items | + / − / Qty / Remove |
| 6 | Quantity adjustment | F2, Ctrl+↑/↓, number pad |
| 7 | Variant selection | Variant picker for multi-variant tiles |
| 8 | Real-time stock availability | Tile badges; checked on every add, across carts |
| 9 | Automatic price calculation | `Cart.recalculate()` |
| 10 | Item-wise discounts | F3 |
| 11 | Cart-wise discounts | F10 |
| 12 | VAT and tax | Per-product VAT %, inclusive or exclusive |
| 13 | Customer selection | F4 |
| 14 | Walk-in sales | Default customer |
| 15 | Retail and wholesale sales | F7, wholesale customers, quantity threshold |
| 16 | Cash payments | Payment screen |
| 17 | Card payments | Payment screen (last 4 digits) |
| 18 | bKash, Nagad, Rocket | Payment screen (TrxID) |
| 19 | Split payments | Several payment lines |
| 20 | Partial payments and due sales | Registered customers |
| 21 | Cash received and change | Payment screen, quick-cash buttons |
| 22 | Hold and resume | F5 / F6 (saved in the database) |
| 23 | Multiple carts | Up to 8 tabs (F8) |
| 24 | Invoice generation | `INV-yyMMdd-#####` |
| 25 | Thermal receipts 58 / 80 mm | Receipt preview → Print |
| 26 | A4 invoice printing | Receipt preview → A4 |
| 27 | PDF invoice download | Receipt preview → Save PDF |
| 28 | Receipt reprinting | Ctrl+P; Sales → View / Reprint |
| 29 | Sales history | Sales → Invoices |
| 30 | Returns and refunds | Sales → Return / Refund |
| 31 | Product exchange | Sales → Exchange (store credit cart) |
| 32 | Sales cancellation | Sales → Cancel Sale (manager) |
| 33 | Automatic inventory updates | Sale, return, cancel and exchange all update stock |
| 34 | Cashier shift management | Shifts |
| 35 | Opening and closing cash | Open / Close Shift (over / short) |
| 36 | Daily sales summary | Reports → Daily Sales Summary; Z report |
| 37 | Product-wise sales reports | Reports → Product-wise Sales |
| 38 | Sales profit calculation | Profit reports; Dashboard |
| 39 | Offline POS support | Runs entirely on local MySQL, so no internet is needed. Held carts are stored in the database. *(There is no queue for selling while MySQL itself is down.)* |
| 40 | Keyboard shortcuts and touchscreen | Section 7; large tiles, keypads and buttons |

### Other
- **Look:** modern UI with a dark mode.
- **Security:** role-based access, SHA-256 password hashing, manager approval for large discounts, login lockout after 5 failed attempts.
- **Setup:** the database is checked and auto-installed on start.

---

## 9. Database

**Script files**
- `db/schema.sql`: tables, keys, checks (stock can't go negative).
- `db/seed.sql`: demo data.

| Table | Purpose |
|---|---|
| `users`, `settings` | Accounts (SHA-256 passwords), shop and POS settings |
| `categories` (self-referencing `parent_id`), `brands`, `units`, `suppliers`, `customers` | Master data |
| `products`, `product_variants` | Product, plus a sellable SKU with prices and stock |
| `bundle_items` | Combo components |
| `product_batches`, `product_serials` | Lots with expiry; serial numbers / IMEI |
| `stock_movements` | Ledger of every stock change (with balance after) |
| `shifts` | Cash shifts |
| `sales`, `sale_items`, `sale_item_allocations` | Invoices, lines (price snapshots), which batch / component was used |
| `payments` | Sale payments and due collections, per method |
| `sales_returns`, `sales_return_items` | Returns, exchanges, cancellations |
| `held_carts` | Carts on hold |

---

## 10. Project structure (MVC)

```
ProductMSbyEasirArafat/
├── pom.xml, nbactions.xml      Maven + NetBeans run configuration
├── run.bat                     compile & run without an IDE
├── lib/                        jars for run.bat (MySQL driver, FlatLaf, ZXing, OpenPDF)
├── db/schema.sql, seed.sql
├── product-images/             created when you add product images
└── src/main/java/com/productms/
    ├── Main.java               start-up: DB check / install, theme, login
    ├── model/                  Product, Variant, Cart, CartItem, Sale, SaleItem, Payment,
    │                           Batch, BundleItem, Customer, Category, Brand, Unit, Supplier,
    │                           Shift, User, ReturnLine
    ├── dao/                    ProductDAO, CustomerDAO, UserDAO, MasterDataDAO, HeldCartDAO
    ├── service/                InventoryService, SaleService, ShiftService, ReportService,
    │                           ReceiptService, LabelService, ImportExportService
    ├── controller/             Login, Main, Dashboard, Pos, Product, Stock, Sales, Customer,
    │                           Shift, Report, Catalog, Settings controllers
    ├── view/                   screens (…View), dialogs (Payment, ReceiptPreview, Return,
    │                           ProductEditor, LabelPrint, pickers, NumberPad), UiKit theme
    ├── db/                     Database (connections, transactions, installer), Jdbc helper
    └── util/                   Money, Settings, Session, BarcodeUtil, Csv, PasswordUtil
```

**The layers**
- **Model:** plain data classes. The pricing rules live in `Cart`.
- **DAO / Service:** all SQL and business rules. Multi-step operations run in a transaction.
- **View:** Swing components only, no SQL.
- **Controller:** listens to the view, calls services, updates the view.

---

## 11. Troubleshooting

| Problem | Fix |
|---|---|
| "Cannot connect to MySQL" on start | Start MySQL in the XAMPP Control Panel → **Retry** |
| "Database not found" | Click **Yes** to create it, or run the PowerShell command in section 1 |
| Login fails for admin | Reload the seed (section 1), or ask another admin to reset your password |
| "No cash shift is open" | Shifts → Open Shift, or turn the rule off in Settings |
| Can't sell an item | Check its stock / expired batches (Stock → Batches & Expiry) and that it is active |
| Strange characters in text | Compile as UTF-8. Maven, NetBeans and `run.bat` already do. |
| Printing | Uses the Windows print dialog. Install your thermal printer's driver, or pick "Microsoft Print to PDF". |
| Reset all data | Re-run `db/schema.sql` + `db/seed.sql`. **This erases everything.** |
