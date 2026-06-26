# LLM Microservices POC — SBCARS 2026

> **Prova de Conceito**: Arquitetura de Agentes Baseados em LLMs para Diagnóstico e Orquestração de Microsserviços em Sistemas Distribuídos

Repositório do artefato de pesquisa submetido ao **SBCARS 2026** (20º Simpósio Brasileiro de Componentes, Arquiteturas e Reutilização de Software), São Paulo, setembro de 2026.

---

## Estrutura do Projeto

```
llm-microservices-poc/
├── api-gateway/              # Spring Cloud Gateway (porta 8080)
├── order-service/            # Gerenciamento de pedidos (porta 8081)
├── payment-service/          # Processamento de pagamentos (porta 8082)
├── inventory-service/        # Controle de estoque (porta 8083)
├── notification-service/     # Notificações via RabbitMQ (porta 8084)
├── agent-orchestrator/       # Camada agentiva LangChain4j+Ollama (porta 8090)
│   └── src/main/java/br/ufla/poc/
│       ├── agent/            # DiagnosticAgent (interface LangChain4j)
│       ├── tool/             # 6 ferramentas controladas (logs, métricas, saúde, topologia, filas, histórico)
│       ├── governance/       # GovernanceService (risco, aprovação humana, rastreabilidade)
│       ├── audit/            # AuditService (persistência de toda interação)
│       ├── service/          # OrchestratorService (fluxo de diagnóstico de 11 etapas)
│       └── controller/       # DiagnosticController (interface humano-agente REST)
├── infra/
│   ├── postgres/             # Schema SQL (audit_log, recommendations, topology, incidents)
│   ├── prometheus/           # Scrape config de todos os serviços
│   ├── loki/                 # Coleta de logs via Promtail
│   ├── tempo/                # Rastreamento distribuído OTLP
│   └── grafana/              # Dashboards e datasources provisionados
├── scripts/
│   ├── run-evaluation.sh     # Executor dos 6 cenários + coleta de métricas
│   └── fault-injection/      # Scripts individuais S1–S6
├── docker-compose.yml
├── docker-compose.ollama.yml # Serviço Ollama isolado (LLM local)
└── setup.sh
```

---

## Arquitetura

A arquitetura implementa as 6 camadas propostas no artigo:

| Camada | Implementação |
|--------|--------------|
| Microsserviços | 5 serviços Spring Boot + PostgreSQL + RabbitMQ + Redis |
| Observabilidade | Prometheus + Loki + Tempo + Grafana |
| Ferramentas e Conectores | `LogQueryTool`, `MetricsQueryTool`, `HealthCheckTool`, `TopologyTool`, `QueueInspectionTool`, `IncidentHistoryTool` |
| Camada Agentiva | `DiagnosticAgent` (LangChain4j → Ollama llama3) |
| Governança e Controle | `GovernanceService` + `AuditService` → tabelas `agent_audit_log`, `agent_recommendations` |
| Interface Humano-Agente | REST API `/api/diagnose` + aprovação humana `/api/diagnose/decision` |

---

## Pré-requisitos

- Docker Engine ≥ 24.x e Docker Compose v2
- 16 GB RAM recomendado (Ollama + todos os serviços)
- 10 GB de espaço em disco (imagens + modelo llama3 ~4.7GB)
- Java 21 + Maven 3.9 (apenas para desenvolvimento local)

---

## Configuração do Ollama (LLM Local)

O Ollama é o runtime responsável por servir o modelo `llama3:8b` localmente. Ele é gerenciado pelo arquivo `docker-compose.ollama.yml`, **separado** do `docker-compose.yml` principal para permitir reinicializações independentes.

### Por que um Compose separado?

- O download do modelo (~4.7 GB) é uma operação única e demorada; separar evita que um `docker compose down` no stack principal destrua o volume com o modelo já baixado.
- Permite habilitar GPU (NVIDIA) de forma opcional sem alterar o compose principal.
- O `agent-orchestrator` se conecta ao Ollama via rede externa (`ollama-net`), mantendo o isolamento entre stacks.

### Passo 1 — Subir o Ollama

```bash
docker compose up -d ollama ollama-pull
```

O serviço `ollama-pull` será iniciado automaticamente assim que o `poc-ollama` ficar **healthy** e fará o download do `llama3:8b`. Acompanhe o progresso:

```bash
docker logs -f ollama-pull
```

Aguarde a mensagem:

```
==> Pull concluído. Modelos disponíveis:
llama3:8b
```

> ⚠️ **Na primeira execução**, o download pode levar de 5 a 15 minutos dependendo da conexão. O container `ollama-pull` encerrará com código 0 ao finalizar — isso é esperado.

### Passo 2 — Verificar o health do servidor

```bash
# Status do container (deve exibir "healthy")
docker ps --filter name=poc-ollama --format "table {{.Names}}\t{{.Status}}"

# Verificação direta da API
curl -s http://localhost:11434/api/tags | python3 -m json.tool
```

Saída esperada com o modelo já baixado:

```json
{
  "models": [
    {
      "name": "llama3:8b",
      ...
    }
  ]
}
```

### Passo 3 — Teste de inferência

Valide que o modelo responde corretamente antes de subir o stack principal:

```bash
curl -s -X POST http://localhost:11434/api/generate \
  -H 'Content-Type: application/json' \
  -d '{
    "model": "llama3:8b",
    "prompt": "Reply with only the word OK if you are working.",
    "stream": false
  }' | python3 -c "import sys,json; print(json.load(sys.stdin)['response'])"
```

Saída esperada: `OK` (ou variação mínima). Qualquer resposta confirma que o modelo está carregado e operacional.

### Ativar GPU NVIDIA (opcional)

Se o host tiver GPU NVIDIA com `nvidia-container-toolkit` instalado, descomente o bloco no `docker-compose.yml`:

```yaml
deploy:
  resources:
    reservations:
      devices:
        - driver: nvidia
          count: all
          capabilities: [gpu]
```

Depois recrie o container:

```bash
docker compose up -d --force-recreate ollama
```

### Gerenciamento do Ollama

```bash
# Parar (preserva o volume com o modelo)
docker compose stop

# Reiniciar sem re-download do modelo
docker compose start

# Ver logs do servidor
docker logs poc-ollama --tail 50 -f

# Listar modelos disponíveis dentro do container
docker exec poc-ollama ollama list

# Pull manual de outro modelo (ex: mistral)
docker exec poc-ollama ollama pull mistral
```

---

## Execução Rápida

```bash
# 1. Suba o Ollama primeiro e aguarde o modelo ser baixado
docker compose up -d
docker logs -f ollama-pull   # aguarde "Pull concluído"

# 2. Suba o stack principal
chmod +x setup.sh
./setup.sh
```

> O `agent-orchestrator` se conecta ao Ollama em `http://poc-ollama:11434`. Certifique-se de que o Ollama está **healthy** antes de subir o stack principal, caso contrário o agente falhará na inicialização.

### Executar a avaliação completa (6 cenários)

```bash
chmod +x scripts/run-evaluation.sh
./scripts/run-evaluation.sh
```

Resultados em `results/<timestamp>/`:
- `evaluation-results.json` — dados brutos por cenário
- `summary.json`            — métricas agregadas (para a Tabela 3 do artigo)
- `S1-response.json` … `S6-response.json` — resposta completa do agente
- `S1-audit.json` … `S6-audit.json`       — trilha de auditoria completa

### Executar cenário individual

```bash
./scripts/run-evaluation.sh S1
```

### Triggering manual via API

```bash
# Diagnóstico manual
curl -X POST http://localhost:8090/api/diagnose \
  -H 'Content-Type: application/json' \
  -d '{
    "scenarioId": "S1",
    "symptomDescription": "Orders are failing. Customers see 503 errors at checkout.",
    "suspectedService": "payment-service",
    "lookbackMinutes": 10
  }' | python3 -m json.tool

# Ver trilha de auditoria
curl http://localhost:8090/api/diagnose/audit/S1 | python3 -m json.tool

# Aprovação humana de uma recomendação
curl -X POST http://localhost:8090/api/diagnose/decision \
  -H 'Content-Type: application/json' \
  -d '{"diagnosticId":"<id>","decision":"APPROVED","decidedBy":"operator@ufla.br"}'
```

---

## Verificação do Ambiente (Checklist)

Execute esta sequência para confirmar que todo o ambiente está operacional antes de rodar os cenários de avaliação:

```bash
# 1. Ollama healthy e modelo disponível
docker ps --filter name=poc-ollama --format "{{.Status}}"
# Esperado: Up X minutes (healthy)

curl -s http://localhost:11434/api/tags | python3 -c \
  "import sys,json; models=[m['name'] for m in json.load(sys.stdin)['models']]; print('Modelos:', models)"
# Esperado: Modelos: ['llama3:8b']

# 2. Stack principal healthy
docker compose ps --format "table {{.Name}}\t{{.Status}}"
# Todos os serviços devem exibir "(healthy)" ou "Up"

# 3. Agent Orchestrator acessível
curl -s http://localhost:8090/actuator/health | python3 -m json.tool
# Esperado: {"status": "UP", ...}

# 4. Prometheus coletando métricas
curl -s "http://localhost:9090/api/v1/targets" | \
  python3 -c "import sys,json; d=json.load(sys.stdin); \
  up=[t['labels']['job'] for t in d['data']['activeTargets'] if t['health']=='up']; \
  print('Targets UP:', len(up))"
# Esperado: Targets UP: 6 (ou mais)

# 5. RabbitMQ com filas criadas
curl -s -u poc:poc123 http://localhost:15672/api/queues | \
  python3 -c "import sys,json; qs=json.load(sys.stdin); \
  print('Filas:', [q['name'] for q in qs])"

# 6. Teste end-to-end mínimo (cenário S1)
curl -s -X POST http://localhost:8090/api/diagnose \
  -H 'Content-Type: application/json' \
  -d '{"scenarioId":"SMOKE","symptomDescription":"smoke test","lookbackMinutes":1}' \
  | python3 -c "import sys,json; d=json.load(sys.stdin); print('Status:', d.get('status','ok'))"
```

---

## Cenários de Falha

| ID | Cenário | Falha injetada | Serviço esperado |
|----|---------|---------------|-----------------|
| S1 | Pagamento indisponível | `payment-service` retornando HTTP 503 | payment-service |
| S2 | Alta latência no estoque | Delay artificial de 3s no `inventory-service` | inventory-service |
| S3 | Fila acumulada | `notification-service` parado (consumer off) | notification-service |
| S4 | Erro de configuração | URL inválida no `order-service` | order-service |
| S5 | Falha de autenticação | Token inválido entre serviços | payment-service |
| S6 | Banco de dados indisponível | PostgreSQL container parado | postgres |

---

## Ferramentas do Agente

Cada ferramenta implementa a interface de acesso controlado da "Camada de Ferramentas e Conectores":

| Ferramenta | Fonte de dados | Parâmetros |
|-----------|---------------|-----------|
| `LogQueryTool.queryLogs()` | Loki API | `serviceName`, `minutesBack` |
| `LogQueryTool.queryAllLogs()` | Loki API | `serviceName`, `minutesBack` |
| `MetricsQueryTool.queryLatency()` | Prometheus API | `serviceName`, `minutesBack` |
| `MetricsQueryTool.queryHttpStatus()` | Prometheus API | `serviceName`, `minutesBack` |
| `MetricsQueryTool.queryJvmMetrics()` | Prometheus API | `serviceName` |
| `HealthCheckTool.checkServiceHealth()` | `/actuator/health` | `serviceName` |
| `HealthCheckTool.checkAllServicesHealth()` | `/actuator/health` (all) | — |
| `TopologyTool.getServiceTopology()` | PostgreSQL knowledge base | — |
| `TopologyTool.getServiceDependencies()` | PostgreSQL knowledge base | `serviceName` |
| `QueueInspectionTool.inspectQueues()` | RabbitMQ Management API | — |
| `QueueInspectionTool.inspectQueue()` | RabbitMQ Management API | `queueName` |
| `IncidentHistoryTool.getIncidentHistory()` | PostgreSQL knowledge base | `serviceName` |
| `IncidentHistoryTool.getRecentIncidents()` | PostgreSQL knowledge base | `days` |

---

## Métricas de Avaliação (Tabela 3 do artigo)

| Critério | Tipo | Como é medido |
|---------|------|---------------|
| Diagnóstico correto | Quantitativo | Serviço afetado + causa identificados corretamente |
| Evidências utilizadas | Quantitativo | Contagem de evidências válidas citadas na resposta |
| Rastreabilidade | Ordinal | Score 0.0–1.0 calculado pelo `GovernanceService` |
| Utilidade técnica | Ordinal | Avaliação manual da aplicabilidade das ações |
| Segurança da recomendação | Ordinal | Verificação de padrões críticos pelo `GovernanceService` |
| Tempo de resposta | Quantitativo | Tempo entre consulta e recomendação (ms) |
| Uso de ferramentas | Quantitativo | Contagem de chamadas de ferramentas por sessão |

---

## URLs de Acesso

| Serviço | URL | Credenciais |
|---------|-----|------------|
| Grafana | http://localhost:3000 | admin / admin123 |
| Prometheus | http://localhost:9090 | — |
| RabbitMQ Management | http://localhost:15672 | poc / poc123 |
| Agent Orchestrator API | http://localhost:8090 | — |
| Order Service | http://localhost:8081 | — |
| Ollama API | http://localhost:11434 | — |

---

## Disponibilidade de Artefatos

Este repositório será disponibilizado publicamente em: `https://anonymous.4open.science/r/llm-microservices-poc`

Os dados derivados das execuções experimentais (resultados dos 6 cenários, trilhas de auditoria, logs anonimizados) serão disponibilizados no Zenodo após a aceitação do artigo.

---

## Tecnologias

- **Java 21** + **Spring Boot 3.3** + **Spring Cloud Gateway**
- **LangChain4j 0.32** — framework de agentes LLM para Java
- **Ollama** — execução local do modelo llama3
- **PostgreSQL 16** — persistência + knowledge base
- **RabbitMQ 3.13** — mensageria assíncrona
- **Redis 7** — cache
- **Prometheus + Loki + Tempo + Grafana** — stack de observabilidade
- **Docker Compose** — orquestração do ambiente

---

*Artefato submetido ao SBCARS 2026 — referência omitida para revisão duplo-anônimo.*