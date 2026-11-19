# Refactor Tool — Frontend

Next.js dashboard for the refactor tool. It visualizes and drives the MAPE-K loop exposed by
`rt-app`: starting iterations, reviewing analysis findings, approving plans, following executions,
and inspecting the aggregate graph.

Main routes:

| Route | Purpose |
|-------|---------|
| `/loop` | The MAPE-K loop cockpit (monitor, analyze, plan, and execute tabs) |
| `/iterations` | Iteration history and details |
| `/versions` | Generated project versions |
| `/graph` | Aggregate/dependency graph (Cytoscape) |
| `/config` | Runtime configuration |

`/analyze`, `/execute`, `/monitor`, and `/plan` redirect to the corresponding `/loop` tab.

## Requirements

- Node.js 20+
- Yarn 4 via corepack (pinned by the `packageManager` field in `package.json`)

## Install

```bash
corepack enable
yarn install
```

## Run

```bash
yarn dev      # development server on http://localhost:3000
```

For a production build:

```bash
yarn build
yarn start
```

The backend (`rt-app`) must be running — see the [repository README](../README.md). Its CORS
configuration already allows `http://localhost:3000`.

## Configuration

The only setting is the backend URL, `NEXT_PUBLIC_RT_API_URL` (default: `http://localhost:8090`).
To change it:

```bash
cp .env.example .env.local
# edit NEXT_PUBLIC_RT_API_URL
```
