CREATE TABLE IF NOT EXISTS stop (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    customer_name TEXT NOT NULL,
    address TEXT NOT NULL,
    lat REAL,
    lon REAL,
    stop_type TEXT NOT NULL,
    deadline TEXT,
    qty_normal_lunchbox INTEGER NOT NULL DEFAULT 0,
    qty_fitness_lunchbox INTEGER NOT NULL DEFAULT 0,
    qty_musli_bar INTEGER NOT NULL DEFAULT 0,
    qty_fruit INTEGER NOT NULL DEFAULT 0,
    qty_risengroed INTEGER NOT NULL DEFAULT 0,
    qty_sandwich INTEGER NOT NULL DEFAULT 0,
    qty_cake INTEGER NOT NULL DEFAULT 0,
    special_order TEXT,
    active INTEGER NOT NULL DEFAULT 1,
    created_at TEXT NOT NULL DEFAULT (datetime('now')),
    preferred_driver_id INTEGER REFERENCES driver(id) ON DELETE SET NULL,
    floor_door TEXT
);

-- Migration for databases created before preferred_driver_id existed. SQLite has no
-- "ADD COLUMN IF NOT EXISTS", so this relies on spring.sql.init.continue-on-error=true:
-- it succeeds once, then harmlessly fails ("duplicate column") on every later startup.
ALTER TABLE stop ADD COLUMN preferred_driver_id INTEGER REFERENCES driver(id) ON DELETE SET NULL;

-- Floor and door ("3. th") kept apart from the address, so the Google Maps link stays clean
-- while the driver still sees it. Same migration trick.
ALTER TABLE stop ADD COLUMN floor_door TEXT;

CREATE TABLE IF NOT EXISTS driver (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    active INTEGER NOT NULL DEFAULT 1,
    end_address TEXT,
    end_lat REAL,
    end_lon REAL
);

-- Migration for databases created before driver.active existed (same trick as above).
ALTER TABLE driver ADD COLUMN active INTEGER NOT NULL DEFAULT 1;

-- Optional per-driver end address (where the driver finishes the route), same migration trick.
ALTER TABLE driver ADD COLUMN end_address TEXT;
ALTER TABLE driver ADD COLUMN end_lat REAL;
ALTER TABLE driver ADD COLUMN end_lon REAL;

CREATE TABLE IF NOT EXISTS route (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    route_date TEXT NOT NULL,
    driver_id INTEGER,
    estimated_minutes REAL NOT NULL DEFAULT 0,
    sequence_index INTEGER NOT NULL DEFAULT 0,
    FOREIGN KEY (driver_id) REFERENCES driver(id) ON DELETE SET NULL
);

CREATE TABLE IF NOT EXISTS route_stop (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    route_id INTEGER NOT NULL,
    stop_id INTEGER NOT NULL,
    stop_order INTEGER NOT NULL,
    FOREIGN KEY (route_id) REFERENCES route(id) ON DELETE CASCADE,
    FOREIGN KEY (stop_id) REFERENCES stop(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS settings (
    key TEXT PRIMARY KEY,
    value TEXT
);

CREATE TABLE IF NOT EXISTS menu_location (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    mobile_pay_number TEXT NOT NULL,
    sort_order INTEGER NOT NULL DEFAULT 0
);

-- A named, reusable snapshot of stops (a "standard day"), so a mostly-fixed stop list
-- doesn't have to be retyped every time — save it once, then apply it before each
-- delivery day and just make the day's 2-3 changes on top.
CREATE TABLE IF NOT EXISTS stop_template (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    created_at TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE TABLE IF NOT EXISTS stop_template_item (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    template_id INTEGER NOT NULL,
    customer_name TEXT NOT NULL,
    address TEXT NOT NULL,
    lat REAL,
    lon REAL,
    stop_type TEXT NOT NULL,
    deadline TEXT,
    qty_normal_lunchbox INTEGER NOT NULL DEFAULT 0,
    qty_fitness_lunchbox INTEGER NOT NULL DEFAULT 0,
    qty_musli_bar INTEGER NOT NULL DEFAULT 0,
    qty_fruit INTEGER NOT NULL DEFAULT 0,
    qty_risengroed INTEGER NOT NULL DEFAULT 0,
    qty_sandwich INTEGER NOT NULL DEFAULT 0,
    qty_cake INTEGER NOT NULL DEFAULT 0,
    special_order TEXT,
    preferred_driver_id INTEGER REFERENCES driver(id) ON DELETE SET NULL,
    floor_door TEXT,
    FOREIGN KEY (template_id) REFERENCES stop_template(id) ON DELETE CASCADE
);

ALTER TABLE stop_template_item ADD COLUMN floor_door TEXT;
