# Product Management System + POS (v3)

A desktop inventory and point-of-sale system for a retail / wholesale shop.

**Built with:** Java 17, Swing with the FlatLaf look and feel, MVC architecture, MySQL / MariaDB through JDBC.
**Libraries:** MySQL Connector/J, FlatLaf, ZXing (barcodes / QR), OpenPDF (PDF invoices), jBCrypt (password hashing).

**What it covers**
- **Products:** variants with custom attributes, SKUs and multiple barcodes, packs (box → pieces), quantity price tiers, MRP / minimum price, scheduled prices and discounts, warranty, tags, multiple images, combos with optional parts, soft delete, cloning, bulk edit, CSV import with preview.
- **Stock:** batches with expiry, serial numbers / IMEI, reservations for held carts, a damaged-stock bucket, stock count sessions, approval of high-value adjustments, a full movement ledger, cost and price history.
- **POS:** barcode / pack / scale / IMEI scanning, favourites and quick buttons, up to N carts, hold with reservation and expiry, price override and discount limits with manager approval, customer credit limits and overdue warnings, split payments over configurable methods (cash, card, bKash, Nagad, Rocket, your own), duplicate-reference detection, store credit, offline selling, cash drawer, customer display, automatic receipt printing, receipt sharing.
- **Money & control:** idempotent checkout, refunds capped at what was paid and returned to the original payment method, cancellation by reversal, cashier shifts with cash in / out / safe drops, denomination counting and per-method reconciliation.
- **Safety:** bcrypt passwords, time-based lockout, forced password change, restricted database account, non-destructive setup with automatic migration, daily backups with verified restore, and an audit log of who changed what.
- **Reports:** 25.

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
9. [Database, migrations and backups](#9-database-migrations-and-backups)
10. [Project structure (MVC)](#10-project-structure-mvc)
11. [Troubleshooting](#11-troubleshooting)

---

## 1. Quick start

**Requirements**
- JDK 17 or newer.
- MySQL / MariaDB on `localhost:3306` (XAMPP works: start MySQL in the XAMPP Control Panel).
- Database name: `ProductMSJibon`.

**First start**
1. Start MySQL.
2. Run the app (see the table below). If the database does not exist, it asks once, then creates it from `db/schema.sql` and loads the demo data from `db/seed.sql`.
3. Sign in with a demo account (section 2). You **must** choose a new password straight away.
4. As admin, open **Settings → Security & Backup** and click **Create restricted database account…** so the app stops using MySQL `root`.

**Setup is non-destructive**
- `schema.sql` never drops anything; it only creates what is missing.
- On every start the app brings an older database up to date: it adds new tables and columns and widens changed columns, and keeps all data.
- Demo data is loaded only into a brand-new, empty database.

**Run**
| Where | How |
|---|---|
| NetBeans | File → Open Project → this folder (Maven project) → Run (F6). `nbactions.xml` sets the main class and the working folder. |
| Without an IDE | Double-click `run.bat`. It compiles with the jars in `lib\` and starts the app. |
| Maven | `mvn package` builds `target/ProductMSbyJibon-2.0.jar` with its libraries in `target/lib`. Run `java -jar target/ProductMSbyJibon-2.0.jar` **from the project folder** (it needs `db/`). |

**Manual install / full reset** (PowerShell). Only the first line erases data:
```powershell
C:\xampp\mysql\bin\mysql.exe -uroot -e "DROP DATABASE IF EXISTS ProductMSJibon"
Get-Content db\schema.sql, db\seed.sql | C:\xampp\mysql\bin\mysql.exe -uroot
```

**Connection settings:** `db.properties` in the project folder (keys `host`, `port`, `user`, `password`, `database`) overrides the defaults (localhost, root, empty password). The app writes this file itself when you create the restricted account. Keep it private; `.gitignore` already excludes it.

**Demo data**
- Users, settings, payment methods, label templates.
- 13 categories, 9 brands, 5 units, 3 suppliers, 3 customers (with credit limits and notes).
- 11 products / 17 variants, including:
  - milk batches (one expired, one expiring);
  - an IMEI-tracked phone with a 12-month warranty;
  - a combo;
  - an out-of-stock and some low-stock items;
  - rice with quantity tiers and a scale PLU;
  - a soft-drink CASE pack barcode;
  - an extra manufacturer barcode on the mouse.

---

## 2. Logins, roles and permissions

| Username | First password | Role |
|---|---|---|
| `admin` | `admin123` | ADMIN |
| `cashier` | `cashier123` | CASHIER |

**Passwords**
- The demo passwords only work once: the app forces a change at first sign-in.
- New passwords need at least 8 characters with letters and numbers, must not contain the username, and must not be a common password.
- Passwords are stored with **bcrypt**. The SHA-256 hashes in `seed.sql` are upgraded to bcrypt automatically at the first sign-in.

**Lockout**
- After 5 wrong passwords (configurable), the account is locked for 15 minutes (configurable).
- The lock is stored in the database, so restarting the app does not reset it.
- An admin can **Unlock** it early.

**Role access**
| Module / action | CASHIER | MANAGER | ADMIN |
|---|:-:|:-:|:-:|
| Dashboard, POS, Sales, Customers, Shifts | ✔ | ✔ | ✔ |
| Products, Stock, Reports, Catalog Setup | – | ✔ | ✔ |
| Settings (shop, rules, hardware, payments, users, security, backups, audit) | – | – | ✔ |
| Cancel a sale, approve adjustments / stock counts | – | ✔ | ✔ |
| Approve another user's limits (manager password prompt) | – | ✔ | ✔ |

**Per-cashier permissions** (Settings → Users & Permissions)
- **May change selling prices:** if off, a price override needs a manager.
- **May sell on credit:** if off, due sales need a manager.
- **Max discount %:** blank means the shop limit.
- **Max invoice amount:** larger invoices need a manager.

Managers and admins are not limited by these, but shop-wide maximums still apply. Every approval stores the approver's username on the sale or return and in the audit log.

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
│ User / role   │                                                              │
│ [Log out]     │                                                              │
└───────────────┴──────────────────────────────────────────────────────────────┘
```

- **Ctrl + number** opens the modules in the order shown to you:
  - Admin: Dashboard 1 … Settings 0.
  - Cashier: Dashboard, POS, Sales, Customers, Shifts = Ctrl+1…5.
- **Top bar:** shift status, clock, **Alerts (n)** (out of stock, low stock, expiring, expired; refreshed every 2 minutes), **Dark / Light mode**.
- **At admin sign-in** a warning lists anything unsafe or pending:
  - root database login with an empty password;
  - a demo password still in use;
  - adjustments waiting for approval;
  - offline sales that failed to sync.
- Closing the window or logging out asks for confirmation (and warns about unfinished carts).

---

## 4. Screens and what every button does

### 4.1 Dashboard
| Area | Content |
|---|---|
| KPI cards | Today's Sales, Today's Gross Profit, Invoices Today (hover for returns), This Month, Stock Value at cost, Customer Dues |
| Alert cards | Low Stock, Out of Stock, Expiring Soon, Expired Batches. Click one to open the Alerts list. |
| Panels | Sales – last 7 days (chart), Top products (30 days), Recent sales, Stock & expiry alerts |
| Buttons | **Refresh**, **+ New Sale** |

### 4.2 POS (Point of Sale)

**Finding products (left side)**
- **Search / scan box (F1).** Type to filter; press Enter to add. It accepts:
  - a barcode, SKU, or **extra barcode**;
  - a **pack barcode**, e.g. the CASE code adds 24 cans;
  - `3*code` to add 3 at once;
  - an **IMEI / serial** to add that exact unit;
  - a **weighed-item label** from a scale: `21` + 5-digit PLU + 5-digit grams + check digit. For example `2100010025005` = 2.500 kg of rice. The prefix is set in Settings.
  - If exactly one tile matches the text, it is added.
- **View filters:** **All / ♥ Favourites / Frequent** (best sellers, 30 days) **/ Recent** (recently sold).
- **Category** filter (includes subcategories), and **Sort** (name, best selling, custom order, price).
- **Quick buttons:** up to 8 favourite items. Click or press **Alt+1 … Alt+8**.
- **Product tiles:**
  - Show price and available stock. Available means sellable stock minus reservations and minus expired / blocked batches.
  - The stripe is green, orange or red for in stock, low or out.
  - ♥ marks a favourite.
  - The tooltip shows the shelf location, reserved quantity, and whether quantity prices exist.

**The cart (right side)**
| Control | What it does |
|---|---|
| Cart tabs, **+ New cart (F8)** | Several carts at once (maximum set in Settings, default 8) |
| Customer button **(F4)** | Search or add a customer. Selecting one shows a pop-up with overdue invoices, credit-limit status, store-credit balance and the customer's notes. A wholesale customer switches the cart to wholesale prices. |
| **Wholesale prices (F7)** | Retail / wholesale for the whole cart |
| Cart table | Item, with markers for [wholesale qty], [qty price], [price changed], [without n part(s)] for combos, the line note, and serials |
| **− / +** (Ctrl+↓ / Ctrl+↑) | Quantity −1 / +1 (serial items open the serial picker) |
| **Qty F2** | Number pad; shows pack sizes as a hint, e.g. "1 CASE = 24 PCS" |
| **Disc F3** | Item discount, amount or %. Above your limit or the shop maximum → manager approval. |
| **Price F11** | Change the unit price. Never above the MRP. Below the minimum price, or without the price-override permission → manager approval. Logged. |
| **Note** | A note on the line, printed on the receipt |
| **♥ Fav** | Add / remove the selected item as a favourite quick button |
| **Remove** (Del) | Remove the line |
| Totals | Subtotal, Discount, VAT (or "VAT (included)"), **TOTAL**. With exchange credit it shows "customer pays X" or the balance and its policy. |
| **Hold (F5)** | Park the cart under a name. It **reserves** its stock (setting) and **expires** after N hours (setting, default 24). |
| **Held carts (F6)** | Resume or discard. Shows the expiry time. Resuming releases the reservation and warns if stock changed. |
| **Cart disc. (F10)** | Cart discount; checked against your limit and the shop maximum % / amount |
| **Void cart** | Clears the cart, after confirmation; logged |
| **Last receipt (Ctrl+P)** | Reprint your last sale |
| **Open drawer** | Opens the cash drawer without a sale (logged) |
| **Customer display** | Shows / hides the customer-facing window |
| **Offline queue (n)** | Sales taken while the database was down. **Sync now**, **Retry failed**, **Discard failed…** (manager). |
| **PAY (F9)** | Payment. If no shift is open, it asks for the opening cash first. The button locks while paying (no double sales). |

**Combos with optional parts:** adding the combo asks which optional parts to include. The price drops by the parts left out, and only included parts leave stock.

**Payment screen**
- **Left:** customer, invoice total, exchange credit, **amount to pay**, paid, remaining / due, **change**, the payment lines (**Remove payment line**), and **Use account credit** (the customer's store-credit balance).
- **Right:**
  - **Method buttons** from Settings → Payment Methods.
  - **Amount**, and a **Reference** field with a live format check (e.g. bKash TrxID = 10 letters/digits; card = last 4 digits or a 6-character approval code).
  - **Quick-cash buttons:** Exact, plus the next round amounts of your notes.
  - **Add payment (Enter)**.
  - **Invoice note**, and the receipt choice: Thermal 80 / 58 mm, A4, none.
- **Bottom:** **Back to cart (Esc)**, **Complete sale (F9 / Ctrl+Enter)**.

**If a limit is broken at checkout** (discount, minimum price, invoice amount, credit limit, overdue dues), a **Manager approval** box lists every reason. A manager types their username and password and the same sale is retried. It is never booked twice.

**After the sale**
- The drawer opens on cash sales (if enabled).
- The customer display says "Your change … Thank you".
- The receipt preview opens, or the receipt prints straight to the configured printer if **automatic printing** is on.
- The preview offers **58 mm / 80 mm / A4**, **Print (Enter)**, **Save PDF**, **Share…** (copy the text, **Send by WhatsApp** with the receipt prefilled for the customer's number, **Send by e-mail**) and **Close**.

### 4.3 Products

**Filters and table**
- **Filters:** search (fuzzy, so typos like "keybord" still find Keyboard), Category, Brand, **Status** (All / Active / Inactive / **Deleted**), **Stock** (All / In / Low / Out / **Has damaged**), **Sort** (name, price, stock, newest, best selling, shelf location).
- **Table:** SKU, Product, Variant, Category, Brand, Unit, Cost, Retail, Wholesale, Stock, Available, Status.
- **Detail panel:**
  - Images, with **< / >** to page through them.
  - Barcode (with its format) and QR code.
  - Extra barcodes and scale PLU.
  - Prices, margin, MRP, minimum price, quantity prices, packs, VAT, discount window, warranty.
  - Stock buckets: sellable, reserved, available, damaged, physical, reorder settings.

**Buttons**
| Button | What it does |
|---|---|
| **+ New Product** (Ctrl+N) / **Edit** / double-click | Product editor |
| **Clone** | Copies the product as an INACTIVE product: no SKU, barcode or stock carried over |
| **Deactivate / Activate** | Hide from / show in the POS |
| **Delete** | Products with any stock or sales history are **soft-deleted** (hidden, history kept). Products never used are removed permanently. |
| **Restore** | Shown with Status = Deleted |
| **Bulk Edit** | For the selected rows: retail % or amount change, wholesale %, VAT, category, brand, reorder level, shelf, active, plus a reason. Prices are checked against minimum price and MRP and recorded in price history. |
| **Schedule Price** | New retail / % change / new wholesale from a date and time. Applied automatically every minute while the app runs, and at start-up. **View schedule** lists them and cancels pending ones. |
| **History** | Tabs: Price history, Cost history (weighted average), Supplier prices (from stock-ins), Change log (audit) |
| **Stock In** | Opens Stock → Stock In with this item |
| **Print Labels** | Label printing (4.11) |
| **Import CSV / Export CSV / CSV Template** | See 5.18 |

**Product editor** (Ctrl+S save, Esc cancel)

*General tab*
- Name, category, brand, unit, type, **tags**.
- VAT %, product discount (none / % / fixed per unit) with **start and end dates**.
- Tracking: batch & expiry *or* serial / IMEI.
- **Warranty (months)**, active.
- **Custom attributes** (one "name: value" per line), description.
- **Images:** several; **Add image… / Make main / Remove**.

*Variants, SKU, Prices & Packs tab* (one row per sellable item)

| Column | Meaning |
|---|---|
| Size, Colour, Weight, **Other attributes** | e.g. `Material=Cotton; Fit=Slim` |
| SKU | Blank = generated from the SKU format in Settings |
| Barcode | Blank = generated. EAN-8 / UPC-A / EAN-13 check digits are verified. |
| Cost, Retail\*, Wholesale, Wholesale from | Prices; wholesale price applies automatically from this quantity |
| **MRP** | Maximum retail price; can never be exceeded |
| **Min price** | Selling below needs approval |
| **Qty price tiers** | e.g. `25=76; 50=74; 100=72` |
| **Packs** | e.g. `CASE=24@660#2000000000190` = 1 CASE is 24 units, sells at 660, has its own barcode |
| **Extra barcodes** | Old packaging or supplier codes |
| **Scale PLU** | 5 digits, for weighed-item labels |
| Reorder at, **Reorder qty**, **Shelf** | Reorder threshold, usual order quantity, location |
| Opening stock | New rows of untracked products only |
| Active | |

Helpers: **+ Add variant**, **Remove variant** (deactivates if used), **Generate size × colour…**, **Copy first row prices to all**.

*Bundle components tab*
- **Combo pricing:** a fixed price, or the sum of the components minus a %.
- **+ Add component**, **Change qty**, **Toggle optional**, **Remove**.
- Shows the price bought separately, the combo price and the saving.

*Saving*
- A **new** product with a similar name or an existing code shows a **possible-duplicate** warning.
- Price changes on an existing product ask for a **reason** for the price history.

### 4.4 Stock
| Tab | Controls |
|---|---|
| **Stock In** | Scan / **Find product…**, quantity with a **unit or pack selector** (e.g. 2 CASE = 48 PCS; asks whether the cost is per unit or per pack), unit cost (shows total value), supplier, reference, batch / manufactured / expiry (batch items), serials one per line (serial items), note → **Receive stock**. Updates the weighted average cost and cost history. |
| **Adjust / Damaged / Expired** | Physical-count helper, type (Adjustment OUT / IN / Damaged / Expired), quantity, batch, serials, reason. If the value is at or above the **approval value** (Settings, default 5,000) and you are not an admin, it becomes a **pending request** instead of applying. |
| **Movement History** | Dates, type, search, **Export CSV**. Every change with quantity, running balance, cost, batch, reference, supplier, note, user. |
| **Batches & Expiry** | All / expiring / expired; **Write off selected batch** (expired → EXPIRED; otherwise → damaged stock) |
| **Damaged Stock** | Items in the damaged bucket (still on premises, not sellable) with value; **Dispose (final loss)** or **Restore to sellable**; log of all damaged / disposed / restored movements |
| **Stock Counts** | **+ New count** (whole shop or one category; system quantities are frozen), **scan to count +1** (or `qty*code`; pack barcodes count the whole pack), **Enter counted qty**, show **All / Differences only**, **Print count sheet**, **Submit for approval**, **Approve & book differences** (another manager, or an admin), **Cancel count** |
| **Approvals** | Pending / all adjustment requests; **Approve** / **Reject** with a note. The requester cannot approve their own (admins can). |
| **Serial Numbers & Warranty** | Search a serial / IMEI, invoice or product: status, invoice, customer, phone, warranty months, **warranty until** and **IN WARRANTY / WARRANTY EXPIRED** |

### 4.5 Sales

**Invoices tab**
- **Filters:** dates, search, status, payment, **Today**, **Export CSV**.
- **Scan an invoice barcode** (from a receipt) into the search box and press Enter to open that invoice directly. Provisional offline numbers (`OFF-…`) also work.
- **Details:** lines (with serials) and payments.
- **Buttons:**
  - **View / Reprint**
  - **Return / Refund**
  - **Exchange**
  - **Collect Due**
  - **Cancel Sale** (manager; disabled once anything was returned)

**Returns, Refunds & Cancellations tab** shows each return: goods value, VAT part, amount refunded, **how it was paid back** (e.g. "CARD 1,312.50, CASH 200.00"), reason, user and approver.

**Return dialog**
- Per line: return quantity and condition (GOOD → back to stock in the same batch; DAMAGED → damaged stock).
- **Refund money** with method **Original method(s)** (default: pays back the way the customer paid, card / mobile first, cash last) or one chosen method; or **Exchange** (store credit for a new cart).
- **Reason** is required.
- Refunds are capped at what was actually paid minus earlier refunds, and any due on the invoice is reduced first.
- Refunds at or above the **refund approval amount** (default 2,000) by a cashier, and returns older than the return period, ask for manager approval.

### 4.6 Customers
- **Filters:** search, **Only customers with due**, **Only overdue**.
- **Columns:** purchases, due, **overdue amount**, **credit limit**, **payment terms**, **store credit**, **notes**, status (ACTIVE / OVERDUE / INACTIVE).
- **Buttons:** **New / Edit** (name, phone, e-mail, address, type, **credit limit** (blank = none, 0 = no credit), **payment terms (days)**, **notes for the cashier**), **Collect Due**, **Purchase History**, **Store Credit** (balance history), **Export CSV**.

### 4.7 Shifts

**Current shift**
- Live summary: opening cash; sales; discounts; VAT; due; received per payment method; due collected; cash and other refunds; **cash in**; **cash out**; **safe drops**; **expected cash in drawer**.
- Cash in / out list.
- **Buttons:**
  - **Open Shift**
  - **Close Shift**
  - **Cash In**, **Cash Out (payout)**, **Safe Drop** (each with a reason; opens the drawer if enabled)
  - **Print X report**
  - **Refresh**

**Closing a shift**
1. Count each note and coin. The total and over / short show live.
2. Enter the **card / mobile totals from your slips and statements** next to the system figures.
3. A note is required for any difference.
4. Close, then optionally print the **Z report**. It includes the reconciliation and the note count.

**Shift history:** over / short and non-cash differences per shift. **Reconciliation** shows expected vs counted / declared per method plus cash movements; **Print selected shift report**.

### 4.8 Reports
- **Controls:** pick a report, set dates (**Today / 7 days / This month / This year**), **Run**, **Export CSV**, **Print**.
- **Footer:** row count and totals.
- Reports marked *(current)* ignore the dates.

| Report | Shows |
|---|---|
| Daily Sales Summary | Per day: invoices, **Gross Sales**, **Discounts**, **Net Sales (ex VAT)**, **VAT**, **Total (incl VAT)**, **Returns (ex VAT)**, **Return VAT**, **Net after Returns**, **Cost of Sales**, **Cost Returned to Stock**, **Gross Profit**, collected, due |
| Product-wise Sales | Qty sold / returned, revenue, returned value, cost, profit net of returns |
| Sales Profit by Invoice | Total, VAT, cost, profit, margin % |
| Product Profit Margins *(current)* | Cost vs retail / wholesale margins |
| Inventory & Stock Valuation *(current)* | Stock, value at cost and at retail, potential profit |
| Low Stock / Out of Stock *(current)* | Items to reorder |
| Expiry Report *(current)* | Expired / expiring batches with value |
| Damaged & Expired Products | Write-offs with loss value |
| Stock Movement Summary | Received, sold, returned, adjusted, written off, net, current |
| Payment Methods | Amount per method and type |
| Sales by Cashier / by Category | Sales and profit |
| Customer Dues *(current)* | Who owes what |
| **Customer Overdue** *(current)* | Due invoices older than each customer's terms |
| Sales Returns | All returns, exchanges, cancellations with refund detail |
| **Refunds by Method** | Refund amounts per method; how much went back to the original payment |
| **Discounts & Price Overrides** | Every discounted or overridden line, with approver |
| **Reorder Suggestions** *(current)* | Suggested order quantity (≥ the usual order quantity, covering 14 days of average sales), last supplier |
| **Stock Buckets** *(current)* | Sellable, reserved, available, expired, damaged, physical, physical value |
| **Price Change History** | Every price change with % and reason |
| **Stock Count Differences** | Count results with value |
| **Cash In / Out & Safe Drops** | All drawer movements |
| **Shift Reconciliation** | Expected vs declared per method per shift |
| **Warranty Register** | Sold items under warranty, serials, warranty end, status |

### 4.9 Catalog Setup
Categories & subcategories, Brands, Units, Suppliers. Each has **+ Add / Edit / Delete**; anything still in use can't be deleted.

### 4.10 Settings (Admin)
**Save settings** (top right) saves every tab. Each change is written to the audit log as "old → new".

| Tab | Contents |
|---|---|
| **General** | **Shop:** name, address, phone, VAT/BIN, currency, receipt footer. **POS & limits:** VAT included in prices, require shift, default receipt, **max open carts**, **held-cart expiry (hours)**, **reserve held stock**, **cashier max discount %**, **max total line discount %**, **max cart discount % and amount**, **refunds needing approval from**, return period, **exchange balance policy** (ACCOUNT / CASH / BLOCK), **cash buttons (notes)**, **product tile order**, **scale barcode prefix**, **allow offline selling**. **Inventory:** expiry alert days, **warn cashier N days before expiry**, **block sale N days before expiry**, **adjustment approval value**, **SKU format** (with example), **generated barcode type**. **Numbering:** invoice / return / cancellation **prefixes**, **number format** (tokens `{PREFIX} {YYYY} {YY} {MM} {DD} {YYMMDD} {SEQ:n}`), **counter reset** (daily / monthly / yearly / never), with a live preview. |
| **Printing & Hardware** | Receipt printer and A4 printer (chosen from installed printers; "(ask every time)" shows the dialog), **automatic receipt printing**, **cash drawer** on the receipt printer with **Test drawer**, **customer display** |
| **Payment Methods** | Add / edit methods: code, name, kind (cash / card / mobile / other), reference required, **reference pattern** (regular expression), hint, colour, order, active. CASH and STORE_CREDIT cannot be deactivated. |
| **Users & Permissions** | Add / edit (role, temporary password, price override, credit sales, max discount %, max invoice amount, active), **Reset password** (forces a change), **Unlock**, **Change my password**. Shows last login and status (LOCKED / MUST CHANGE PASSWORD). At least one active admin is always kept. |
| **Security & Backup** | Database connection (warns about root with an empty password), **Create restricted database account…**, lockout attempts and minutes, **automatic daily backup**, backups to keep, **Back up now**, **Validate a backup file…**, **Restore from backup…**, **Open backup folder**, list of backup files |
| **Audit Log** | Dates, entity (invoice, product, variant, customer, user, settings, shift, cart, stock count, payment method), search, **Export CSV** |

### 4.11 Barcode label printing (Products → Print Labels)
- **Item list:** product, barcode, editable **copies**. **+ Add product** / **Remove**.
- **Templates** (stored in the database): A4 38×21 (65 per sheet), A4 64×34 (24), roll 50×25, roll 40×30 with MRP.
  - **Custom size…** (width, height, columns and rows on A4, or roll).
  - **Save as template…**, **Delete**.
- **Content:** shop name, name, price, **MRP**, **SKU**, QR code, wholesale price, **text size**.
- Barcodes print as EAN-8 / UPC-A / EAN-13 when the code is a valid one of those, otherwise Code-128.
- **Preview** of the first sheet / label, then **Print…**.

---

## 5. Workflows (step by step)

### 5.1 First-time setup (Admin)
1. Sign in as `admin` / `admin123` and choose a new password.
2. **Settings → Security & Backup → Create restricted database account…**, restart the app, and set a MySQL root password (XAMPP: phpMyAdmin → User accounts).
3. **Settings → General:** shop details, VAT style, limits, numbering. **Save settings**.
4. **Settings → Printing & Hardware:** pick the receipt printer and enable the drawer and display if you have them.
5. **Settings → Payment Methods:** check the reference patterns and add your own methods.
6. **Settings → Users & Permissions:** add cashiers with their limits.
7. **Catalog Setup:** categories, brands, units, suppliers.
8. **Products:** add products, or **CSV Template → Import CSV**.
9. **Stock → Stock In:** opening stock (batch and serial items).
10. **Products → Print Labels** for items without a barcode.

### 5.2 Add a product with sizes, colours, packs and quantity prices
1. **+ New Product.** General tab: name, category, brand, unit, VAT, tags, warranty, attributes, images.
2. Variants tab: fill row 1 (cost, retail, wholesale, MRP, min price, tiers such as `10=600; 50=560`, packs such as `BOX=12@6800`), then **Generate size × colour…**.
3. **Save.** If a similar product exists you are warned first.

### 5.3 Create a combo with an optional part
1. **+ New Product** → type **Bundle / Combo**.
2. Bundle tab: **+ Add component** (×2 or more), select one → **Toggle optional**.
3. Pricing: a fixed price on the Variants tab, or **sum of components minus %**.
4. At the POS, the cashier ticks or unticks the optional part; the price and stock follow.

### 5.4 Receive goods (stock in)
1. **Stock → Stock In**, scan, choose **unit or pack** (e.g. CASE), enter quantity and cost.
2. Choose the supplier and enter the invoice number.
3. Enter the batch and expiry, or the IMEIs, when the product needs them.
4. **Receive stock.** The average cost changes and is recorded in Cost history.

### 5.5 Physical stock count (session)
1. **Stock → Stock Counts → + New count**, choosing the whole shop or a category.
2. Scan every item on the shelf (each scan = +1; packs count fully), or select a row → **Enter counted qty**. **Print count sheet** for a paper count.
3. **Submit for approval.**
4. Another manager or an admin opens it, shows **Differences only**, and clicks **Approve & book differences**. Differences become adjustments. Batch and serial items are listed for manual adjustment.

### 5.6 Damaged goods
1. **Stock → Adjust**, type **Damaged**, quantity and reason. High-value items go to **Approvals** first.
2. The units move to **Damaged Stock** (not sellable).
3. From there, either **Dispose** (thrown away or returned to the supplier: final loss) or **Restore to sellable** (repaired).

### 5.7 A normal sale
1. Open a shift (or let PAY ask for the opening cash).
2. Scan items, tap tiles or press Alt+1…8 for favourites.
3. **F9**, then enter the cash received or tap a quick-cash button → **Complete sale**.
4. The drawer opens and the receipt prints or shows.

### 5.8 Split / mobile payment
1. **F9** → **bKash** → amount → TrxID (format checked) → **Add payment**.
2. **Cash** → the rest → **Complete**.
3. A TrxID already used on another invoice is refused.

### 5.9 Due / credit sale
1. **F4** to select the customer. The pop-up shows overdue invoices, credit limit, store credit and notes.
2. **F9**, take part payment or none, **Complete**, then confirm the due.
3. A manager must approve if:
   - the due would exceed the customer's **credit limit**;
   - the customer has **overdue** invoices;
   - the cashier may not sell on credit.
4. Collect later: **Customers → Collect Due**. Payment goes to the oldest invoices first.

### 5.10 Price override and discounts
1. Select a line → **Price F11** → the new price. It can never be above the MRP. A manager must approve if the cashier lacks the permission or the price is below the minimum.
2. **Disc F3** / **Cart disc. F10**: above your limit or the shop maximum, a manager approves on the spot.

### 5.11 Hold, reserve and resume
1. **F5** names the cart. Its stock is reserved, so other tills cannot sell it.
2. **F6** → **Resume** releases the reservation.
3. Held carts not resumed within the expiry hours are discarded automatically and their reservations released.

### 5.12 Return and refund to the original method
1. **Sales** → scan the receipt barcode → **Return / Refund**.
2. Quantities and condition, **Original method(s)**, reason → **Process return**.
3. The message shows exactly how to pay back, e.g. "Card 1,312.50 (orig. ref 4821)". Large refunds need a manager.

### 5.13 Exchange
1. **Sales → Exchange** → the items → a new POS cart opens with the credit.
2. Add the replacement items. The cart shows what the customer pays, or the balance.
3. **F9.** A balance is handled by the **exchange balance policy**:
   - **ACCOUNT** (default) adds it to the customer's store credit;
   - **CASH** pays it out;
   - **BLOCK** requires items worth at least the credit.

### 5.14 Cancel a sale (manager)
1. **Sales → Cancel Sale** → reason and refund method (default original).
2. The invoice and its payments are **kept**. A `CXL-…` reversal records the goods, VAT, cost and the refund for each original payment.
3. Stock, batches and serials are restored.

### 5.15 Database down: offline selling
1. If MySQL stops while paying, the POS offers to **save the sale offline** and prints a provisional `OFF-…` receipt.
2. Every minute (or **Offline queue → Sync now**) the sale is booked with the same key, so never twice. The invoice keeps the `OFF-…` reference.
3. If a sale can no longer be booked (e.g. the stock was sold meanwhile), it shows as **FAILED** for a manager: **Retry** after fixing, or **Discard** with a reason.

### 5.16 End of day
1. **Shifts → Close Shift.** Count notes and coins, enter the card / mobile totals from slips, and explain any difference.
2. Print the **Z report**.
3. **Reports → Daily Sales Summary / Payment Methods / Shift Reconciliation** → Today.

### 5.17 Backups and restore
- **Backups:** a daily automatic backup is taken at start-up. **Back up now** any time.
- **Restore:**
  1. **Validate** the file (checksum).
  2. **Restore** and type `RESTORE`.
  3. A safety backup of the current data is taken first.
  4. After restoring, row counts and the stock ledger are verified, and everyone signs in again.

### 5.18 Bulk import / export
1. **Export CSV** exports all variants with every column. **CSV Template** gives an example file.
2. **Import CSV** shows a **preview first**: each line marked CREATE / UPDATE / ERROR with the reason. **Nothing is saved yet.**
3. **Import** writes everything in **one transaction**. If "all or nothing" is ticked, any error cancels the import; otherwise invalid rows are skipped. A failure while saving always rolls everything back.
4. Columns: `sku, barcode, product_name, description, category, subcategory, brand, unit, size, colour, weight, extra_attributes, purchase_price, retail_price, wholesale_price, wholesale_min_qty, mrp, min_price, vat_percent, discount_type, discount_value, reorder_level, reorder_qty, location, tags, warranty_months, price_tiers, packs, extra_barcodes, opening_stock, active`

---

## 6. Business rules

**Checkout**
- The sale, its lines, stock deduction, payments and invoice number are **one transaction**: all succeed or nothing is saved.
- Each checkout carries a unique key (`client_txn_id`). A retry, a double click or an offline re-sync returns the first sale and never creates a second.

**Concurrent tills**
- Each stock change locks the item's row. Two tills selling the last units at the same moment cannot oversell: one succeeds, the other gets "not enough stock". This is tested.

**Stock buckets**
- **Sellable** = `stock_qty`.
- **Reserved** = held carts.
- **Available** = sellable − reserved − expired / near-expiry-blocked batches.
- **Damaged** = quarantined, not sellable.
- **Physical** = sellable + damaged.

**Batches**
- Sold first-expiry-first-out.
- Expired batches, and batches inside the "block sale" window, are never sold.
- The cashier is warned when the batch being sold expires within the warning window.

**Costs**
- The product cost is a **weighted average**, updated by every stock-in and stored in cost history.
- Each sale line stores the **actual cost snapshot**: batch items use their own batch cost.
- Returns store the cost put back. Profit reports use these snapshots, never today's cost.

**Pricing order, per line**
1. List price: override, or retail (combo: fixed, or sum − %).
2. Lowest qualifying quantity price: tier, pack price, or wholesale-from quantity; wholesale mode uses the wholesale price.
3. Product discount, if today is inside its window.
4. Cashier item discount.
5. Share of the cart discount.
6. VAT per line, rounded half-up to 2 decimals.

**Rounding:** invoice totals are sums of the rounded lines. Returning the last units of a line returns exactly what is left of that line, so VAT and totals never drift.

**Limits**
- Line discount above the shop maximum, cashier discount above the personal limit, below minimum price, price override without permission, invoice above the cashier's limit, cart discount above maximum % / amount, due above credit limit, due with overdue invoices, credit sale without permission, refund at or above the approval amount, return after the return period: each needs a manager.
- **Exceptions:** the MRP is a hard limit, and offline sales can't get approvals.

**Payments**
- References are format-checked per method.
- The same method + reference can never be used twice.
- Card / mobile cannot exceed the amount owed; only cash gives change.
- Due sales need a registered customer.

**Refunds**
- Never more than paid minus already refunded; any due is reduced first.
- Each refund row is linked to the original payment it reverses.

**Cancellation:** a reversal record. Nothing is deleted.

**Expected cash** = opening + cash received − cash refunded + cash in − cash out − safe drops.

**Numbers:** gap-free per prefix and period (locked counter inside the sale transaction). Defaults: `INV-yyMMdd-0001`, `RET-…`, `CXL-…`, `CNT-…` (stock counts).

**Deletion:** products, categories, brands, units and suppliers with history are never physically deleted.

**Audit:** sign-ins, lockouts, sales, approvals, price overrides and changes, stock changes, counts, returns, cancellations, drawer openings, held / voided carts, users, payment methods, settings, imports, backups and restores.

---

## 7. Keyboard shortcuts

| Where | Key | Action |
|---|---|---|
| Anywhere | Ctrl+1 … Ctrl+0 | Switch module |
| POS | F1 | Search / scan |
| POS | F2 | Quantity |
| POS | F3 | Item discount |
| POS | F4 | Customer |
| POS | F5 | Hold |
| POS | F6 | Held carts |
| POS | F7 | Wholesale |
| POS | F8 | New cart |
| POS | F9 | Pay |
| POS | F10 | Cart discount |
| POS | F11 | Change price |
| POS | Alt+1 … Alt+8 | Favourite quick buttons |
| POS | Ctrl+↑ / Ctrl+↓ | Quantity ±1 |
| POS | Ctrl+P | Reprint last receipt |
| POS search box | Enter / Esc / ↓ | Add / clear / go to cart |
| POS cart | Del, numpad + / − | Remove line, quantity |
| Payment | Enter | Add payment, or complete when fully paid |
| Payment | F9 or Ctrl+Enter | Complete sale |
| Payment | Esc | Back to cart |
| Receipt | Enter / Esc | Print / close |
| Products | Ctrl+N / Ctrl+F | New / search |
| Product editor | Ctrl+S / Esc | Save / cancel |

Touch screens: the tiles, quick buttons, number pads, method buttons and big PAY button are all tap-sized.

---

## 8. Feature checklist

**Status key:** ✔ done and tested · ◐ done locally, but connecting outside hardware or a paid API is left as an integration point.

### 8.1 Critical technical & business-rule changes
| Area | Implementation | Status |
|---|---|:-:|
| Password security | bcrypt (cost 12); legacy SHA-256 upgraded at sign-in; strength rules | ✔ |
| Login security | Lockout stored in the database, time based (attempts and minutes in Settings), admin unlock | ✔ |
| Demo credentials | Forced password change at first sign-in; admin warning while a demo password is in use | ✔ |
| Database configuration | **Create restricted database account** (rights on this database only, no DROP); warning while using root without a password | ✔ |
| Database setup | `schema.sql` non-destructive; automatic migration on start; seed only on an empty database | ✔ |
| Atomic checkout | One transaction for sale, lines, stock, payments, number | ✔ |
| Concurrent sales | Row locks; tested with two simultaneous tills | ✔ |
| Idempotent payments | `client_txn_id` unique key; double click and retry protection | ✔ |
| Sellable stock | Expired, near-expiry-blocked and reserved stock excluded everywhere | ✔ |
| Refund validation | Capped at paid minus previous refunds; due reduced first | ✔ |
| Exchange excess credit | Defined policy: ACCOUNT / CASH / BLOCK | ✔ |
| Cash shifts | Cash in / out / safe drops in expected cash | ✔ |
| Tax consistency | Per-line VAT on discounted net, half-up rounding; VAT stored on returns; exact last-unit returns | ✔ |
| Cost snapshots | Sale and return cost snapshots; cost history | ✔ |
| Offline mode | Distinguishes "no internet" (always fine, local DB) from "database down" (offline queue) | ✔ |
| Database recovery | Daily automatic backup, checksum validation, safety backup and verified restore, migrations | ✔ |
| Audit | Audit log of prices, stock, discounts, invoices, settings, users, sign-ins | ✔ |

### 8.2 Product Management – modifications
| Feature | Implementation | Status |
|---|---|:-:|
| SKU | Unique across SKUs, barcodes, extra barcodes, pack barcodes, PLUs; custom SKU format | ✔ |
| Barcodes | EAN-8, UPC-A, EAN-13 (check-digit validation), Code-128 | ✔ |
| Variants | Custom "other attributes" per variant + product attributes | ✔ |
| Prices | Price history with dates and reasons; scheduled prices | ✔ |
| Wholesale | Multiple quantity tiers (+ pack prices, wholesale-from qty) | ✔ |
| Discounts | Start / end dates | ✔ |
| Images | Multiple per product | ✔ |
| Stock | Available / reserved / damaged / physical buckets | ✔ |
| Adjustments | Approval above a value threshold | ✔ |
| Deletion | Soft delete with restore | ✔ |
| Expiry | Configurable warning and sale-block windows | ✔ |
| Import | Preview, validation, all-or-nothing transaction | ✔ |
| Costing | Weighted average (product) vs batch cost (sale) shown separately | ✔ |
| Bundles | Optional components; fixed or sum-minus-% pricing | ✔ |
| Search | Fuzzy, more filters, sorting | ✔ |
| Units | Packs (Box → PCS) at POS, stock-in and counts | ✔ |

### 8.3 Product Management – new features
| # | Feature | Where | Status |
|---|---|---|:-:|
| 1 | Duplicate detection | Warning when saving a new product | ✔ |
| 2 | Cloning | Products → Clone | ✔ |
| 3 | Custom attributes | Editor (product + variant) | ✔ |
| 4 | Tags | Editor; searchable | ✔ |
| 5 | Multiple images | Editor; detail panel < / > | ✔ |
| 6 | Price history | Products → History | ✔ |
| 7 | Multiple wholesale tiers | Variant "Qty price tiers" | ✔ |
| 8 | Scheduled price changes | Products → Schedule Price | ✔ |
| 9 | Scheduled discounts | Discount start / end | ✔ |
| 10 | Price override permissions | User permission + manager approval | ✔ |
| 11 | Warranty period | Editor (months); printed on receipt | ✔ |
| 12 | Warranty status | Stock → Serial Numbers & Warranty; Warranty Register report | ✔ |
| 13 | Minimum selling price | Variant "Min price" | ✔ |
| 14 | MRP | Variant "MRP"; labels | ✔ |
| 15 | Shelf / rack location | Variant "Shelf"; tooltip; count order | ✔ |
| 16 | Reorder quantity | Variant "Reorder qty" | ✔ |
| 17 | Reorder suggestions | Reports → Reorder Suggestions | ✔ |
| 18 | Stock reservation | Held carts reserve stock | ✔ |
| 19 | Counting sessions | Stock → Stock Counts | ✔ |
| 20 | Audit differences | Differences view; Stock Count Differences report | ✔ |
| 21 | Adjustment approval | Stock → Approvals | ✔ |
| 22 | Count barcode scanning | Stock Counts scan box | ✔ |
| 23 | Unit conversion | Packs | ✔ |
| 24 | Packaging units | Packs with own price / barcode | ✔ |
| 25 | Supplier price history | Products → History → Supplier prices | ✔ |
| 26 | Cost history | Products → History → Cost history | ✔ |
| 27 | Multiple barcodes | Variant "Extra barcodes" | ✔ |
| 28 | Custom label templates | Label dialog: templates, custom size, save | ✔ |
| 29 | Bulk editing | Products → Bulk Edit | ✔ |
| 30 | Change audit history | Products → History → Change log; Audit Log | ✔ |

### 8.4 POS – modifications
| Feature | Implementation | Status |
|---|---|:-:|
| Search | Favourites, frequent, recent, quick buttons | ✔ |
| Multiple carts | Maximum configurable | ✔ |
| Hold | Expiry and reservation policy | ✔ |
| Real-time stock | Re-validated with row locks inside the checkout transaction | ✔ |
| Item / cart discounts | Combined line maximum, cart maximum % and amount, personal limits | ✔ |
| Payments | Reference format validation, duplicate prevention | ✔ |
| Due sales | Credit limits, terms, overdue warnings | ✔ |
| Cash | Denomination buttons from Settings | ✔ |
| Invoices | Prefixes, format and reset period | ✔ |
| Refunds | Linked to original payments | ✔ |
| Exchanges | Replacement cart with difference shown; balance policy | ✔ |
| Shifts | Cash in / out / safe drops | ✔ |
| Offline | Local queue, automatic sync, failed-sale review | ✔ |
| Cancellation | Reversal records | ✔ |
| Reports | Gross / net / VAT / refunds / profit separated | ✔ |

### 8.5 POS – new features
| # | Feature | Where | Status |
|---|---|---|:-:|
| 1–4 | Favourites, frequent, recent, quick buttons | POS view filters, Alt+1…8 | ✔ |
| 5 | Custom sorting | Sort combo; custom order | ✔ |
| 6 | Quantity prefix | `3*code` | ✔ |
| 7 | Weighed barcodes | Scale prefix + PLU + grams | ✔ |
| 8–9 | Price override + approval | F11 | ✔ |
| 10 | Max discount validation | POS + checkout | ✔ |
| 11–12 | Credit limit, due warnings | Customer pop-up, checkout | ✔ |
| 13 | Duplicate reference detection | Checkout, due collection | ✔ |
| 14 | Reference verification | Format rules per method. **Live checks with bKash / Nagad / Rocket need their merchant APIs (not connected).** | ◐ |
| 15 | Custom payment methods | Settings → Payment Methods | ✔ |
| 16 | Payment terminal | Approval code entered by the cashier. **A card terminal SDK is not connected.** | ◐ |
| 17 | Cash drawer | ESC/POS kick through the receipt printer. **Needs a drawer to test.** | ◐ |
| 18–19 | Cash in / out, safe drops | Shifts | ✔ |
| 20 | Denomination counting | Close shift | ✔ |
| 21 | Printer auto-selection | Settings → Printing & Hardware | ✔ |
| 22 | Automatic receipt printing | Setting | ✔ |
| 23 | Customer display | Second window (second monitor if present) | ✔ |
| 24 | Digital receipt sharing | Copy / WhatsApp / e-mail from the receipt. **No SMS gateway; attach the PDF yourself.** | ◐ |
| 25 | Invoice prefixes | Settings → Numbering | ✔ |
| 26 | Invoice search by barcode | Sales search box | ✔ |
| 27 | Refund to original method | Return dialog default | ✔ |
| 28 | Refund approval | Above the approval amount | ✔ |
| 29 | Exchange difference | Exchange cart | ✔ |
| 30–32 | Offline queue, auto sync, failed recovery | POS → Offline queue | ✔ |
| 33 | Duplicate checkout prevention | Idempotency key + locked PAY button | ✔ |
| 34 | POS audit logs | Audit Log | ✔ |
| 35 | Cashier sales limits | Max invoice amount | ✔ |
| 36 | Cashier pricing permissions | Users & Permissions | ✔ |
| 37 | Parked cart expiration | Held-cart expiry | ✔ |
| 38 | Customer purchase notes | Customer notes shown at the POS | ✔ |
| 39 | Sale item notes | Note button; printed | ✔ |
| 40 | Shift reconciliation | Close shift + Reconciliation | ✔ |

### 8.6 Original features (v1–v2)
All 70 original features are still in place:
- **Products:** CRUD, categories, brands, variants, SKU, barcode / QR, labels, images, units, prices, wholesale / retail, discounts, stock, stock in / out, adjustments, alerts, expiry, batch / serial, damaged / expired, returns, bundles, movement history, valuation, search, import / export, activation, reports, margins, automatic deduction.
- **POS:** dashboard, search, scanner, cart, quantity, variants, live stock, pricing, discounts, VAT, customers, walk-in, retail / wholesale, cash / card / mobile, split, partial / due, change, hold / resume, multiple carts, invoices, 58 / 80 mm, A4, PDF, reprint, history, returns, exchange, cancellation, inventory updates, shifts, opening / closing cash, daily summary, product sales, profit, offline, shortcuts and touch.

---

## 9. Database, migrations and backups

**Files**
- `db/schema.sql`: 41 tables. Safe to run any time: `CREATE ... IF NOT EXISTS` only.
- `db/seed.sql`: demo data, for an empty database only.

**Migrations**
- On every start, `com.productms.db.Migrations` runs `schema.sql`, then compares every column with the live database.
- It adds missing columns and widens changed ones (e.g. `payments.method` ENUM → VARCHAR, password hash length).
- The result is recorded as `schema_version = 3`. Upgrading a v2 database is tested: 54 changes, all data kept.

| Group | Tables |
|---|---|
| Users & control | `users`, `settings`, `audit_log`, `doc_sequences`, `payment_methods` |
| Master data | `categories`, `brands`, `units`, `suppliers`, `customers`, `customer_credit_txns` |
| Products | `products`, `product_images`, `product_variants`, `variant_barcodes`, `variant_packs`, `price_tiers`, `price_history`, `scheduled_prices`, `cost_history`, `bundle_items`, `product_batches`, `product_serials` |
| Stock | `stock_movements`, `damaged_stock_log`, `stock_reservations`, `stock_counts`, `stock_count_items`, `adjustment_requests`, `label_templates` |
| POS & money | `shifts`, `cash_movements`, `shift_reconciliation`, `sales`, `sale_items`, `sale_item_allocations`, `payments`, `sales_returns`, `sales_return_items`, `refund_payments`, `held_carts` |

**Backups**
- Written by the app itself; no `mysqldump` needed.
- Stored in `backups/` (the newest N are kept), one `.sql` file each, ending with a SHA-256 checksum.
- **Restore** refuses tampered or incomplete files and takes a safety backup first. Afterwards it checks every table's row count and the stock ledger.

**Offline queue:** sales are stored in `offline-queue/` until synced. Synced files move to `offline-queue/synced`, discarded ones to `offline-queue/discarded`.

---

## 10. Project structure (MVC)

```
ProductMSbyEasirArafat/
├── pom.xml, nbactions.xml      Maven + NetBeans run configuration
├── run.bat                     compile & run without an IDE (jars in lib/)
├── lib/                        mysql-connector-j, flatlaf, zxing core, openpdf, jbcrypt
├── db/schema.sql, seed.sql     non-destructive schema, demo data
├── backups/, offline-queue/    created at run time (git-ignored, like db.properties)
├── product-images/             product photos copied in by the editor
└── src/main/java/com/productms/
    ├── Main.java               start-up: DB check, migration, auto backup, scheduler, login
    ├── model/                  Product, Variant, Cart (pricing engine), CartItem, Sale, SaleItem, Payment,
    │                           PaymentMethod, Batch, BundleItem, Customer, Shift, User, ...
    ├── dao/                    ProductDAO, CustomerDAO, UserDAO, PaymentMethodDAO, MasterDataDAO, HeldCartDAO
    ├── service/                SaleService (checkout / returns / cancel / dues), InventoryService, StockCountService,
    │                           ShiftService, ReportService, ReceiptService, LabelService, ImportExportService,
    │                           BackupService, OfflineQueue, DocNumbers, AuditService, HardwareService, SchedulerService
    ├── controller/             one controller per screen
    ├── view/                   screens, dialogs (Payment, ReceiptPreview, Return, ProductEditor, LabelPrint,
    │                           ManagerApproval, ChangePassword, CustomerDisplay, TableDialog, pickers), UiKit
    ├── db/                     Database (connections, transactions, setup, restricted account), Migrations, Jdbc
    └── util/                   Money, Settings, Session, BarcodeUtil, PasswordUtil, Csv, AppException,
                                ApprovalRequiredException
```

**The layers**
- **Model:** data, plus the pricing rules in `Cart`.
- **DAO / Service:** all SQL and business rules. Multi-step operations run in one transaction.
- **View:** Swing only, no SQL.
- **Controller:** connects the two.

---

## 11. Troubleshooting

| Problem | Fix |
|---|---|
| "Cannot connect to MySQL" | Start MySQL in XAMPP → **Retry** |
| "Database does not exist" | Click **Yes**; it is created with demo data |
| Locked out | Wait for the lock time, or an admin clicks **Unlock** (Settings → Users) |
| Forgot the only admin password | In MySQL: `UPDATE users SET password_hash=SHA2('Temp1234',256), must_change_password=1, locked_until=NULL WHERE username='admin';` then sign in and change it |
| After creating the restricted account the app cannot connect | Check `db.properties`; delete it to go back to root while you fix it |
| "No cash shift is open" | Shifts → Open Shift (or turn the rule off in Settings) |
| An item will not sell | Check stock buckets (reserved, expired / near-expiry batches, damaged) and that it is active |
| "Manager approval needed" | A limit from section 6 was hit; a manager enters their credentials |
| Offline sale FAILED | POS → Offline queue: read the problem, fix stock, **Retry**, or **Discard** with a reason |
| Receipt prints to the wrong printer / dialog appears | Settings → Printing & Hardware → choose the printer |
| Drawer does not open | It must be cabled to the receipt printer, and that printer selected and enabled; use **Test drawer** |
| Restore refused | The file is not a ProductMS backup, is incomplete, or was edited (checksum) |
