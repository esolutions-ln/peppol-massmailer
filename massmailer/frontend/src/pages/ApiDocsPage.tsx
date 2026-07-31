export default function ApiDocsPage() {
  return (
    <>
      <div className="topbar">
        <span className="topbar-title">API Documentation</span>
        <a
          href="/v3/api-docs"
          target="_blank"
          rel="noopener noreferrer"
          className="btn btn-secondary btn-sm"
        >
          OpenAPI JSON ↗
        </a>
      </div>
      <div className="content">
        <div className="page-header">
          <h2>API Documentation</h2>
          <p>Interactive REST API reference powered by Swagger UI</p>
        </div>
        <div className="card" style={{ padding: 0, overflow: 'hidden' }}>
          <iframe
            src="/swagger-ui.html"
            title="API Documentation"
            style={{
              width: '100%',
              height: 'calc(100vh - 220px)',
              border: 'none',
              background: '#fff',
              borderRadius: 12,
              display: 'block',
            }}
          />
        </div>
      </div>
    </>
  )
}
