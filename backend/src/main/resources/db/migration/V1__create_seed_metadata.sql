-- 创建演示数据的元数据表。
CREATE TABLE IF NOT EXISTS app_users (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(64) NOT NULL UNIQUE,
    email VARCHAR(254) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL
);

CREATE TABLE IF NOT EXISTS merchants (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    category VARCHAR(255) NOT NULL,
    minimum_order DECIMAL(12, 2) NOT NULL,
    delivery_fee DECIMAL(12, 2) NOT NULL,
    delivery_minutes INTEGER NOT NULL,
    open BOOLEAN NOT NULL,
    discount_threshold DECIMAL(12, 2) NOT NULL,
    discount_amount DECIMAL(12, 2) NOT NULL
);

CREATE TABLE IF NOT EXISTS dishes (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    merchant_id BIGINT NOT NULL,
    category VARCHAR(255) NOT NULL,
    name VARCHAR(255) NOT NULL,
    description VARCHAR(2000) NOT NULL,
    price DECIMAL(12, 2) NOT NULL,
    stock INTEGER NOT NULL,
    listed BOOLEAN NOT NULL,
    image_url VARCHAR(255) NOT NULL,
    CONSTRAINT fk_dishes_merchant FOREIGN KEY (merchant_id) REFERENCES merchants(id)
);

CREATE TABLE IF NOT EXISTS dish_options (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    dish_id BIGINT NOT NULL,
    group_name VARCHAR(255) NOT NULL,
    option_name VARCHAR(255) NOT NULL,
    price_delta DECIMAL(12, 2) NOT NULL,
    available BOOLEAN NOT NULL,
    CONSTRAINT fk_dish_options_dish FOREIGN KEY (dish_id) REFERENCES dishes(id)
);

CREATE TABLE IF NOT EXISTS addresses (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    recipient VARCHAR(255) NOT NULL,
    phone VARCHAR(255) NOT NULL,
    detail VARCHAR(255) NOT NULL,
    default_address BOOLEAN NOT NULL,
    CONSTRAINT fk_addresses_user FOREIGN KEY (user_id) REFERENCES app_users(id)
);

CREATE TABLE IF NOT EXISTS cart_drafts (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    merchant_id BIGINT NOT NULL,
    CONSTRAINT fk_cart_drafts_user FOREIGN KEY (user_id) REFERENCES app_users(id),
    CONSTRAINT fk_cart_drafts_merchant FOREIGN KEY (merchant_id) REFERENCES merchants(id)
);

CREATE TABLE IF NOT EXISTS cart_draft_items (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    draft_id BIGINT NOT NULL,
    dish_id BIGINT NOT NULL,
    quantity INTEGER NOT NULL,
    CONSTRAINT fk_cart_items_draft FOREIGN KEY (draft_id) REFERENCES cart_drafts(id),
    CONSTRAINT fk_cart_items_dish FOREIGN KEY (dish_id) REFERENCES dishes(id)
);

CREATE TABLE IF NOT EXISTS cart_draft_item_options (
    item_id BIGINT NOT NULL,
    option_id BIGINT NOT NULL,
    CONSTRAINT fk_cart_item_options_item FOREIGN KEY (item_id) REFERENCES cart_draft_items(id)
);

CREATE TABLE IF NOT EXISTS food_orders (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL,
    request_fingerprint VARCHAR(255) NOT NULL,
    payment_idempotency_key VARCHAR(255),
    address_recipient VARCHAR(255) NOT NULL,
    address_phone VARCHAR(255) NOT NULL,
    address_detail VARCHAR(255) NOT NULL,
    subtotal DECIMAL(12, 2) NOT NULL,
    delivery_fee DECIMAL(12, 2) NOT NULL,
    discount DECIMAL(12, 2) NOT NULL,
    total DECIMAL(12, 2) NOT NULL,
    status VARCHAR(255) NOT NULL,
    CONSTRAINT uk_food_orders_user_key UNIQUE (user_id, idempotency_key),
    CONSTRAINT fk_food_orders_user FOREIGN KEY (user_id) REFERENCES app_users(id)
);

CREATE TABLE IF NOT EXISTS order_items (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id BIGINT NOT NULL,
    dish_id BIGINT NOT NULL,
    dish_name VARCHAR(255) NOT NULL,
    quantity INTEGER NOT NULL,
    unit_price DECIMAL(12, 2) NOT NULL,
    option_summary VARCHAR(255) NOT NULL,
    CONSTRAINT fk_order_items_order FOREIGN KEY (order_id) REFERENCES food_orders(id)
);

CREATE TABLE IF NOT EXISTS order_item_options (
    item_id BIGINT NOT NULL,
    option_id BIGINT NOT NULL,
    CONSTRAINT fk_order_item_options_item FOREIGN KEY (item_id) REFERENCES order_items(id)
);

CREATE TABLE IF NOT EXISTS order_timeline (
    order_id BIGINT NOT NULL,
    status VARCHAR(255),
    CONSTRAINT fk_order_timeline_order FOREIGN KEY (order_id) REFERENCES food_orders(id)
);

CREATE TABLE IF NOT EXISTS seed_dish_metadata (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    dish_id BIGINT NOT NULL,
    dish_name VARCHAR(255) NOT NULL,
    previous_price DECIMAL(12, 2),
    price_changed BOOLEAN NOT NULL,
    rating DECIMAL(3, 1) NOT NULL,
    spiciness VARCHAR(255) NOT NULL,
    ingredients VARCHAR(2000) NOT NULL,
    allergens VARCHAR(2000) NOT NULL
);

CREATE TABLE IF NOT EXISTS seed_address_metadata (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    address_id BIGINT NOT NULL,
    detail VARCHAR(255) NOT NULL,
    valid BOOLEAN NOT NULL
);
