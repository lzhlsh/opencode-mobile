import { createInterface } from "node:readline"
import { create as createOpenCode } from "./opencode"

const Source = {
  repository: "https://github.com/anomalyco/opencode",
  version: "2.0.32",
  commit: "60a5a6ca7ce176b04cd2321ffd1819b1ea8e6006",
}

interface RuntimeConfig {
  readonly apiKey: string
  readonly baseURL: string
  readonly contextLimit: number
  readonly databasePath: string
  readonly model: string
  readonly outputLimit: number
  readonly projectRoot: string
}

interface Request {
  readonly id: string | number
  readonly method: string
  readonly params?: Record<string, unknown>
}

function runtimeConfig(): RuntimeConfig {
  const value = process.env.OPENCODE_MOBILE_CONFIG_JSON
  if (!value) throw new Error("OPENCODE_MOBILE_CONFIG_JSON is not set")

  const parsed: unknown = JSON.parse(value)
  if (typeof parsed !== "object" || parsed === null || Array.isArray(parsed)) {
    throw new Error("OpenCode Mobile settings must be a JSON object")
  }
  const config = parsed as Record<string, unknown>
  const required = (key: keyof RuntimeConfig) => {
    const entry = config[key]
    if (typeof entry !== "string" || entry.trim().length === 0) {
      throw new Error(`Missing OpenCode Mobile setting: ${key}`)
    }
    return entry
  }

  const baseURL = required("baseURL")
  if (!baseURL.startsWith("https://")) {
    throw new Error("The model API must use HTTPS")
  }
  return {
    apiKey: required("apiKey"),
    baseURL,
    databasePath: required("databasePath"),
    model: required("model"),
    contextLimit: typeof config.contextLimit === "number" ? config.contextLimit : 128_000,
    outputLimit: typeof config.outputLimit === "number" ? config.outputLimit : 8_192,
    projectRoot: required("projectRoot"),
  }
}

function coreConfig(config: RuntimeConfig) {
  const filePermissions = ["read", "edit", "glob", "grep"].map((action) => ({
    action,
    resource: "*",
    effect: "allow" as const,
  }))
  const permissions = [{ action: "*", resource: "*", effect: "deny" as const }, ...filePermissions]
  const providerID = "mobile"

  return {
    update: "disable",
    plugins: [],
    mcp: {},
    default_agent: "mobile",
    model: `${providerID}/${config.model}`,
    providers: {
      [providerID]: {
        name: "OpenCode Mobile API",
        package: "@opencode/ai/providers/openai-compatible",
        settings: {
          apiKey: config.apiKey,
          baseURL: config.baseURL,
          timeout: false,
          headerTimeout: false,
          chunkTimeout: false,
          provider: providerID,
        },
        models: {
          [config.model]: {
            name: config.model,
            capabilities: {
              tools: true,
              input: ["text"],
              output: ["text"],
            },
            cost: { input: 0, output: 0 },
            limit: { context: config.contextLimit, output: config.outputLimit },
          },
        },
      },
    },
    agents: {
      mobile: {
        description: "OpenCode Android agent restricted to the granted project workspace",
        mode: "primary",
        permissions,
      },
    },
  }
}

function write(value: unknown) {
  process.stdout.write(`${JSON.stringify(value)}\n`)
}

function startProgress(host: Awaited<ReturnType<typeof createOpenCode>>, sessionID: string) {
  const controller = new AbortController()
  const events = host.events.subscribe({ signal: controller.signal })
  const task = (async () => {
    let lastMessage = ""
    let lastSent = 0
    try {
      for await (const event of events) {
        const data = event.data as { sessionID?: unknown; name?: unknown }
        if (data.sessionID !== sessionID) continue

        let message = ""
        switch (event.type) {
          case "session.step.started":
            message = "已连接模型，正在处理请求…"
            break
          case "session.reasoning.started":
            message = "模型正在思考…"
            break
          case "session.text.delta":
            message = "模型已开始生成回复…"
            break
          case "session.tool.input.started":
            message = typeof data.name === "string" && /^[a-zA-Z0-9_-]{1,48}$/.test(data.name)
              ? `正在调用工具：${data.name}`
              : "正在调用项目分析工具…"
            break
          case "session.tool.success":
            message = "工具执行完成，模型正在继续分析…"
            break
          case "session.tool.failed":
            message = "工具执行遇到问题，模型正在处理…"
            break
          case "session.retry.scheduled":
            message = "模型连接暂时异常，正在自动重试…"
            break
          case "session.compaction.started":
            message = "正在整理长对话上下文…"
            break
        }

        const now = Date.now()
        if (message && (message !== lastMessage || now - lastSent >= 15_000)) {
          write({ type: "progress", message })
          lastMessage = message
          lastSent = now
        }
      }
    } catch {
      if (!controller.signal.aborted) {
        write({
          type: "progress",
          message: "进度事件暂时中断，模型请求仍在继续…",
        })
      }
    }
  })()

  return async () => {
    controller.abort()
    await task
  }
}

async function handle(
  host: Awaited<ReturnType<typeof createOpenCode>>,
  config: RuntimeConfig,
  request: Request,
) {
  const params = request.params ?? {}
  switch (request.method) {
    case "status":
      return { source: Source, runtime: Bun.version }
    case "session.create": {
      const directory = params.directory
      if (typeof directory !== "string" || directory !== config.projectRoot) {
        throw new Error("Session directory must be the authorized project workspace")
      }
      return host.sessions.create({
        title: typeof params.title === "string" ? params.title : undefined,
        location: { directory },
        agent: "mobile",
        model: { providerID: "mobile", id: config.model },
      })
    }
    case "session.list":
      return host.sessions.list({
        directory: config.projectRoot,
        limit: 100,
        order: "desc",
      })
    case "session.delete": {
      const sessionID = params.sessionID
      if (typeof sessionID !== "string") throw new Error("session.delete requires a sessionID")
      const session = await host.sessions.get({ sessionID })
      if (session.location.directory !== config.projectRoot) {
        throw new Error("Cannot delete a session outside the authorized project workspace")
      }
      await host.sessions.remove({ sessionID })
      return { deleted: sessionID }
    }
    case "session.prompt": {
      const sessionID = params.sessionID
      const text = params.text
      if (typeof sessionID !== "string" || typeof text !== "string" || !text.trim()) {
        throw new Error("session.prompt requires a sessionID and non-empty text")
      }
      return host.sessions.prompt({ sessionID, text })
    }
    case "session.respond": {
      const directory = params.directory
      const text = params.text
      if (directory !== config.projectRoot) {
        throw new Error("Session directory must be the authorized project workspace")
      }
      if (typeof text !== "string" || !text.trim()) {
        throw new Error("session.respond requires non-empty text")
      }

      const existingSessionID = params.sessionID
      let sessionID: string
      if (typeof existingSessionID === "string" && existingSessionID) {
        sessionID = existingSessionID
      } else {
        const session = await host.sessions.create({
          title: text.trim().slice(0, 100),
          location: { directory: config.projectRoot },
          agent: "mobile",
          model: { providerID: "mobile", id: config.model },
        })
        sessionID = session.id
      }

      const session = await host.sessions.get({ sessionID })
      if (session.location.directory !== config.projectRoot) {
        throw new Error("Session is outside the authorized project workspace")
      }
      await host.sessions.switchModel({
        sessionID,
        model: { providerID: "mobile", id: config.model },
      })
      write({ type: "progress", message: "请求已发送，正在等待模型响应…" })
      const stopProgress = startProgress(host, sessionID)
      try {
        await host.sessions.prompt({ sessionID, text })
        await host.sessions.wait({ sessionID })
      } finally {
        await stopProgress()
      }
      const messages = await host.message.list({ sessionID, limit: 50, order: "desc" })
      return { sessionID, messages }
    }
    case "session.messages": {
      const sessionID = params.sessionID
      if (typeof sessionID !== "string") throw new Error("session.messages requires a sessionID")
      return host.message.list({
        sessionID,
        limit: typeof params.limit === "number" ? Math.min(Math.max(params.limit, 1), 100) : 50,
        order: "desc",
      })
    }
    default:
      throw new Error(`Unsupported OpenCode Mobile method: ${request.method}`)
  }
}

async function run() {
  const config = runtimeConfig()
  const host = await createOpenCode({
    app: { name: "opencode", version: Source.version, channel: "local" },
    database: { path: config.databasePath },
    fs: { filewatcher: false, fff: false },
    config: {
      project: false,
      content: JSON.stringify(coreConfig(config)),
    },
  })
  write({ type: "ready", ...Source, runtime: Bun.version })

  const input = createInterface({ input: process.stdin, crlfDelay: Infinity })
  try {
    for await (const line of input) {
      let request: Request
      try {
        request = JSON.parse(line) as Request
        if ((typeof request.id !== "string" && typeof request.id !== "number")
          || typeof request.method !== "string") {
          throw new Error("Each request needs an id and method")
        }
      } catch (error) {
        write({ type: "error", error: error instanceof Error ? error.message : String(error) })
        continue
      }

      try {
        write({ id: request.id, result: await handle(host, config, request) })
      } catch (error) {
        write({
          id: request.id,
          error: error instanceof Error ? error.message : String(error),
        })
      }
    }
  } finally {
    input.close()
    await host.close()
  }
}

if (import.meta.main) {
  run().catch((error: unknown) => {
    write({
      type: "fatal",
      error: error instanceof Error ? error.message : String(error),
    })
    process.exitCode = 1
  })
}
