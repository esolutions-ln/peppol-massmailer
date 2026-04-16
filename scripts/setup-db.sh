#!/usr/bin/env bash
# =============================================================================
# setup-db.sh — Creates the massmailer database and mailer user on localhost
# Run as a user with psql access to the postgres superuser account
# Usage: ./scripts/setup-db.sh [db_password]
# =============================================================================
set -e

DB_NAME="massmailer"
DB_USER="postgres"
DB_PASS="${1:-mailer_secret}"

echo "Setting up database '$DB_NAME' with user '$DB_USER'..."

sudo -u postgres psql <<EOF
-- Create user if not exists
DO \$\$
BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = '${DB_USER}') THEN
    CREATE USER ${DB_USER} WITH PASSWORD '${DB_PASS}';
    RAISE NOTICE 'User ${DB_USER} created.';
  ELSE
    ALTER USER ${DB_USER} WITH PASSWORD '${DB_PASS}';
    RAISE NOTICE 'User ${DB_USER} already exists — password updated.';
  END IF;
END
\$\$;

-- Create database if not exists
SELECT 'CREATE DATABASE ${DB_NAME} OWNER ${DB_USER}'
  WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = '${DB_NAME}')\gexec

-- Grant privileges
GRANT ALL PRIVILEGES ON DATABASE ${DB_NAME} TO ${DB_USER};
EOF

# Grant schema privileges (must be run connected to the target DB)
sudo -u postgres psql -d "$DB_NAME" <<EOF
GRANT ALL ON SCHEMA public TO ${DB_USER};
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES TO ${DB_USER};
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON SEQUENCES TO ${DB_USER};
EOF

echo ""
echo "Done. Connection details:"
echo "  Host:     localhost"
echo "  Port:     5432"
echo "  Database: $DB_NAME"
echo "  User:     $DB_USER"
echo "  Password: $DB_PASS"
echo ""
echo "Update your .env:"
echo "  DB_HOST=localhost"
echo "  DB_PORT=5432"
echo "  POSTGRES_PASSWORD=$DB_PASS"
echo "  DB_USER=$DB_USER"
