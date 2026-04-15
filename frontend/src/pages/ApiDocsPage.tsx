export default function ApiDocsPage() {
  return (
    <div>
      <div className="page-header">
        <div>
          <h2>API Documentation</h2>
          <p style={{ color: '#94a3b8', marginTop: 4 }}>Interactive REST API reference powered by Swagger UI</p>
        </div>
        <a
          href="/v3/api-docs"
          target="_blank"
          rel="noopener noreferrer"
          className="btn btn-secondary"
          style={{ fontSize: 13 }}
        >
          OpenAPI JSON
        </a>
      </div>
      <div className="card" style={{ padding: 0, overflow: 'hidden' }}>
        <iframe
          src="/swagger-ui.html"
          title="API Documentation"
          style={{
            width: '100%',
            height: 'calc(100vh - 180px)',
            border: 'none',
            background: '#fff',
            borderRadius: 8,
          }}
        />
      </div>
    </div>
  )
}
