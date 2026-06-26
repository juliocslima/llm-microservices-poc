-- Orders schema
CREATE TABLE IF NOT EXISTS orders (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id VARCHAR(100) NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    total_amount NUMERIC(12,2) NOT NULL,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS order_items (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id UUID REFERENCES orders(id),
    product_id VARCHAR(100) NOT NULL,
    quantity INT NOT NULL,
    unit_price NUMERIC(10,2) NOT NULL
);

-- Payments schema
CREATE TABLE IF NOT EXISTS payments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id UUID NOT NULL,
    amount NUMERIC(12,2) NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMPTZ DEFAULT NOW(),
    processed_at TIMESTAMPTZ
);

-- Inventory schema
CREATE TABLE IF NOT EXISTS products (
    id VARCHAR(100) PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    stock_quantity INT NOT NULL DEFAULT 0,
    reserved_quantity INT NOT NULL DEFAULT 0
);

INSERT INTO products (id, name, stock_quantity) VALUES
  ('PROD-001', 'Notebook Pro', 50),
  ('PROD-002', 'Mouse Wireless', 200),
  ('PROD-003', 'Teclado Mecânico', 150),
  ('PROD-004', 'Monitor 27"', 30),
  ('PROD-005', 'Headset USB', 100)
ON CONFLICT DO NOTHING;

-- Audit log for agent interactions
CREATE TABLE IF NOT EXISTS agent_audit_log (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    scenario_id VARCHAR(50),
    agent_name VARCHAR(100) NOT NULL,
    action_type VARCHAR(100) NOT NULL,
    tool_name VARCHAR(100),
    tool_input JSONB,
    tool_output TEXT,
    prompt TEXT,
    response TEXT,
    evidence_count INT DEFAULT 0,
    timestamp TIMESTAMPTZ DEFAULT NOW(),
    duration_ms BIGINT
);

-- Agent recommendations
CREATE TABLE IF NOT EXISTS agent_recommendations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    scenario_id VARCHAR(50),
    affected_service VARCHAR(100),
    probable_cause TEXT,
    evidence_used JSONB,
    recommended_actions JSONB,
    risk_level VARCHAR(20),
    requires_human_approval BOOLEAN DEFAULT false,
    human_decision VARCHAR(20),
    human_decision_at TIMESTAMPTZ,
    traceability_score NUMERIC(3,2),
    diagnosis_correct BOOLEAN,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- Service topology (knowledge base)
CREATE TABLE IF NOT EXISTS service_topology (
    service_name VARCHAR(100) PRIMARY KEY,
    description TEXT,
    dependencies JSONB,
    criticality VARCHAR(20) DEFAULT 'MEDIUM',
    port INT
);

INSERT INTO service_topology (service_name, description, dependencies, criticality, port) VALUES
  ('api-gateway',      'Ponto de entrada da plataforma, roteia requisições para os serviços',
    '["order-service"]', 'HIGH', 8080),
  ('order-service',    'Gerencia o ciclo de vida dos pedidos',
    '["payment-service","inventory-service","rabbitmq","postgres","redis"]', 'HIGH', 8081),
  ('payment-service',  'Processa pagamentos dos pedidos',
    '["postgres","rabbitmq"]', 'HIGH', 8082),
  ('inventory-service','Controla estoque e reservas de produtos',
    '["postgres","rabbitmq"]', 'MEDIUM', 8083),
  ('notification-service','Envia notificações via mensageria',
    '["rabbitmq"]', 'LOW', 8084)
ON CONFLICT DO NOTHING;

-- Incident history (knowledge base)
CREATE TABLE IF NOT EXISTS incident_history (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    service_name VARCHAR(100),
    incident_type VARCHAR(100),
    description TEXT,
    resolution TEXT,
    occurred_at TIMESTAMPTZ DEFAULT NOW()
);

INSERT INTO incident_history (service_name, incident_type, description, resolution) VALUES
  ('payment-service', 'SERVICE_UNAVAILABLE',
   'payment-service returned HTTP 503 for all requests. Health check endpoint showed DOWN status. All order checkouts failed with payment errors.',
   'Restarted payment-service container. Root cause was OOM kill due to memory leak. Added memory limits and alerting.'),
  ('payment-service', 'HIGH_ERROR_RATE',
   'payment-service processing failed for 95% of requests. HTTP 503 responses observed in order-service logs under FAULT_INJECTION label.',
   'Disabled fault injection flag via /api/payments/fault endpoint. Service recovered immediately.'),
  ('inventory-service', 'HIGH_LATENCY',
   'inventory-service reserve endpoint taking 3000ms+ per request due to artificial delay injection. P99 latency spike visible in Prometheus.',
   'Disabled artificial delay via /api/inventory/fault endpoint. Normal latency (< 100ms) restored.'),
  ('notification-service', 'QUEUE_ACCUMULATION',
   'poc.notifications RabbitMQ queue accumulated thousands of messages with 0 active consumers. notification-service container was stopped.',
   'Restarted notification-service. Consumer reconnected to RabbitMQ and processed backlog.'),
  ('order-service', 'CONFIGURATION_ERROR',
   'order-service configured with invalid INVENTORY_SERVICE_URL pointing to non-existent host. All inventory calls failed with connection refused.',
   'Updated INVENTORY_SERVICE_URL environment variable to correct hostname. Redeployed order-service.'),
  ('postgres', 'DATABASE_UNAVAILABLE',
   'PostgreSQL container stopped. All services depending on postgres (order-service, payment-service, inventory-service) failed with JDBC connection errors and HikariPool exhaustion.',
   'Restarted postgres container. All services reconnected automatically via HikariCP retry.')
ON CONFLICT DO NOTHING;
