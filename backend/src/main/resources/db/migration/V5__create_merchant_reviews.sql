-- 创建商家评价表及其约束。
CREATE TABLE merchant_reviews (
    id BIGINT NOT NULL AUTO_INCREMENT,
    merchant_id BIGINT NOT NULL,
    reviewer_name VARCHAR(80) NOT NULL,
    rating DECIMAL(2,1) NOT NULL,
    content VARCHAR(1000) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_merchant_reviews_merchant FOREIGN KEY (merchant_id) REFERENCES merchants(id),
    CONSTRAINT uk_merchant_review_seed UNIQUE (merchant_id, reviewer_name, created_at)
);

CREATE INDEX idx_merchant_reviews_page ON merchant_reviews (merchant_id, created_at DESC, id DESC);
