package dbconn

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"strconv"
	"strings"

	"pxf-cli/gplog"
	"pxf-cli/operating"
	"github.com/jackc/pgx/v5/pgconn"
	_ "github.com/jackc/pgx/v5/stdlib"
)

// SQLSTATEs surfaced by handleConnectionError. pgx wraps server errors in a
// *pgconn.PgError; classifying by code is more reliable than substring
// matching the message text.
const (
	sqlstateUndefinedObject   = "42704" // raised for nonexistent role
	sqlstateUndefinedDatabase = "3D000"
)

/*
 * DBConn maintains its own connection pool of *sql.DB slots (each capped at
 * exactly one open connection) so callers can fan out work across NumConns
 * goroutines and have per-goroutine session affinity. Exec/Select/Get default
 * to slot 0; pass an explicit slot to use a different session.
 */
type DBConn struct {
	ConnPool []*sql.DB
	NumConns int
	Driver   DBDriver
	User     string
	DBName   string
	Host     string
	Port     int
	Tx       []*sql.Tx
	Version  GPDBVersion
}

type DBDriver interface {
	Connect(driverName string, dataSourceName string) (*sql.DB, error)
}

type GPDBDriver struct{}

func (driver *GPDBDriver) Connect(driverName string, dataSourceName string) (*sql.DB, error) {
	db, err := sql.Open(driverName, dataSourceName)
	if err != nil {
		return nil, err
	}
	if err := db.Ping(); err != nil {
		_ = db.Close()
		return nil, err
	}
	return db, nil
}

func NewDBConnFromEnvironment(dbname string) *DBConn {
	if dbname == "" {
		gplog.Fatal(errors.New("No database provided"), "")
	}

	username := operating.System.Getenv("PGUSER")
	if username == "" {
		currentUser, _ := operating.System.CurrentUser()
		username = currentUser.Username
	}
	host := operating.System.Getenv("PGHOST")
	if host == "" {
		host, _ = operating.System.Hostname()
	}
	port, err := strconv.Atoi(operating.System.Getenv("PGPORT"))
	if err != nil {
		port = 5432
	}

	return NewDBConn(dbname, username, host, port)
}

func NewDBConn(dbname, username, host string, port int) *DBConn {
	if dbname == "" {
		gplog.Fatal(errors.New("No database provided"), "")
	}
	if username == "" {
		gplog.Fatal(errors.New("No username provided"), "")
	}
	if host == "" {
		gplog.Fatal(errors.New("No host provided"), "")
	}

	return &DBConn{
		Driver:  &GPDBDriver{},
		User:    username,
		DBName:  dbname,
		Host:    host,
		Port:    port,
		Version: GPDBVersion{},
	}
}

func (dbconn *DBConn) MustBegin(whichConn ...int) {
	gplog.FatalOnError(dbconn.Begin(whichConn...))
}

func (dbconn *DBConn) Begin(whichConn ...int) error {
	connNum := dbconn.ValidateConnNum(whichConn...)
	if dbconn.Tx[connNum] != nil {
		return errors.New("Cannot begin transaction; there is already a transaction in progress")
	}
	tx, err := dbconn.ConnPool[connNum].BeginTx(context.Background(), &sql.TxOptions{Isolation: sql.LevelSerializable})
	if err != nil {
		return err
	}
	dbconn.Tx[connNum] = tx
	return nil
}

func (dbconn *DBConn) Close() {
	if dbconn.ConnPool != nil {
		for _, conn := range dbconn.ConnPool {
			if conn != nil {
				_ = conn.Close()
			}
		}
		dbconn.ConnPool = nil
		dbconn.Tx = nil
		dbconn.NumConns = 0
	}
}

func (dbconn *DBConn) MustCommit(whichConn ...int) {
	gplog.FatalOnError(dbconn.Commit(whichConn...))
}

func (dbconn *DBConn) Commit(whichConn ...int) error {
	connNum := dbconn.ValidateConnNum(whichConn...)
	if dbconn.Tx[connNum] == nil {
		return errors.New("Cannot commit transaction; there is no transaction in progress")
	}
	err := dbconn.Tx[connNum].Commit()
	dbconn.Tx[connNum] = nil
	return err
}

func (dbconn *DBConn) MustRollback(whichConn ...int) {
	gplog.FatalOnError(dbconn.Rollback(whichConn...))
}

func (dbconn *DBConn) Rollback(whichConn ...int) error {
	connNum := dbconn.ValidateConnNum(whichConn...)
	if dbconn.Tx[connNum] == nil {
		return errors.New("Cannot rollback transaction; there is no transaction in progress")
	}
	err := dbconn.Tx[connNum].Rollback()
	dbconn.Tx[connNum] = nil
	return err
}

func (dbconn *DBConn) MustConnect(numConns int) {
	gplog.FatalOnError(dbconn.Connect(numConns))
}

func (dbconn *DBConn) Connect(numConns int, utilityMode ...bool) error {
	if numConns < 1 {
		return fmt.Errorf("Must specify a connection pool size that is a positive integer")
	}
	if dbconn.ConnPool != nil {
		return fmt.Errorf("The database connection must be closed before reusing the connection")
	}

	dbname := EscapeConnectionParam(dbconn.DBName)
	user := EscapeConnectionParam(dbconn.User)
	krbsrvname := operating.System.Getenv("PGKRBSRVNAME")
	if krbsrvname == "" {
		krbsrvname = "postgres"
	}
	sslmode := operating.System.Getenv("PGSSLMODE")
	if sslmode == "" {
		sslmode = "prefer"
	}
	// statement_cache_capacity=0 disables pgx's automatic prepared-statement
	// cache: re-creating an object with the same name in one session triggers
	// a cache-lookup failure on GPDB 4 otherwise. default_query_exec_mode=exec
	// keeps queries on the simple protocol so the server sees them as
	// individual statements.
	connStr := fmt.Sprintf(`user='%s' dbname='%s' krbsrvname='%s' host=%s port=%d sslmode='%s' statement_cache_capacity=0 default_query_exec_mode=exec`,
		user, dbname, krbsrvname, dbconn.Host, dbconn.Port, sslmode)

	dbconn.ConnPool = make([]*sql.DB, numConns)
	if len(utilityMode) > 1 {
		return fmt.Errorf("The utility mode parameter accepts exactly one boolean value")
	} else if len(utilityMode) == 1 && utilityMode[0] {
		// gp_role (GPDB 7+) replaced gp_session_role (GPDB 6 and earlier).
		// The version isn't known until after we connect, so probe with
		// gp_session_role and fall back to gp_role if the GUC is unknown.
		roleConnStr := connStr + " gp_role=utility"
		sessionRoleConnStr := connStr + " gp_session_role=utility"
		utilConn, err := dbconn.Driver.Connect("pgx", sessionRoleConnStr)
		if utilConn != nil {
			_ = utilConn.Close()
		}
		if err != nil {
			if strings.Contains(err.Error(), `unrecognized configuration parameter "gp_session_role"`) {
				connStr = roleConnStr
			} else {
				return dbconn.handleConnectionError(err)
			}
		} else {
			connStr = sessionRoleConnStr
		}
	}

	for i := 0; i < numConns; i++ {
		conn, err := dbconn.Driver.Connect("pgx", connStr)
		err = dbconn.handleConnectionError(err)
		if err != nil {
			return err
		}
		conn.SetMaxOpenConns(1)
		conn.SetMaxIdleConns(1)
		dbconn.ConnPool[i] = conn
	}
	dbconn.Tx = make([]*sql.Tx, numConns)
	dbconn.NumConns = numConns
	version, err := InitializeVersion(dbconn)
	if err != nil {
		return fmt.Errorf("Failed to determine database version: %w", err)
	}
	dbconn.Version = version
	return nil
}

func (dbconn *DBConn) handleConnectionError(err error) error {
	if err == nil {
		return nil
	}
	var pgErr *pgconn.PgError
	if errors.As(err, &pgErr) {
		switch pgErr.Code {
		case sqlstateUndefinedObject:
			// pgx emits this for "role <X> does not exist". Disambiguate from
			// other undefined-object errors (e.g. missing GUC) by checking
			// that the message mentions the role we tried to connect as.
			if strings.Contains(pgErr.Message, "role") && strings.Contains(pgErr.Message, dbconn.User) {
				return fmt.Errorf(`Role "%s" does not exist on %s:%d, exiting`, dbconn.User, dbconn.Host, dbconn.Port)
			}
		case sqlstateUndefinedDatabase:
			return fmt.Errorf(`Database "%s" does not exist on %s:%d, exiting`, dbconn.DBName, dbconn.Host, dbconn.Port)
		}
	}
	if strings.Contains(err.Error(), "connection refused") {
		return fmt.Errorf(`could not connect to server: Connection refused
	Is the server running on host "%s" and accepting
	TCP/IP connections on port %d?`, dbconn.Host, dbconn.Port)
	}
	return fmt.Errorf("%v (%s:%d)", err, dbconn.Host, dbconn.Port)
}

func (dbconn *DBConn) Exec(query string, whichConn ...int) (sql.Result, error) {
	connNum := dbconn.ValidateConnNum(whichConn...)
	if dbconn.Tx[connNum] != nil {
		return dbconn.Tx[connNum].Exec(query)
	}
	return dbconn.ConnPool[connNum].Exec(query)
}

func (dbconn *DBConn) MustExec(query string, whichConn ...int) {
	_, err := dbconn.Exec(query, whichConn...)
	gplog.FatalOnError(err)
}

func (dbconn *DBConn) query(connNum int, query string) (*sql.Rows, error) {
	if dbconn.Tx[connNum] != nil {
		return dbconn.Tx[connNum].Query(query)
	}
	return dbconn.ConnPool[connNum].Query(query)
}

func (dbconn *DBConn) Get(destination interface{}, query string, whichConn ...int) error {
	connNum := dbconn.ValidateConnNum(whichConn...)
	rows, err := dbconn.query(connNum, query)
	if err != nil {
		return err
	}
	defer rows.Close()
	return scanOne(destination, rows)
}

func (dbconn *DBConn) Select(destination interface{}, query string, whichConn ...int) error {
	connNum := dbconn.ValidateConnNum(whichConn...)
	rows, err := dbconn.query(connNum, query)
	if err != nil {
		return err
	}
	defer rows.Close()
	return scanAll(destination, rows)
}

func (dbconn *DBConn) ValidateConnNum(whichConn ...int) int {
	if len(whichConn) == 0 {
		return 0
	}
	if len(whichConn) != 1 {
		gplog.Fatal(fmt.Errorf("At most one connection number may be specified for a given connection"), "")
	}
	if whichConn[0] < 0 || whichConn[0] >= dbconn.NumConns {
		gplog.Fatal(fmt.Errorf("Invalid connection number: %d", whichConn[0]), "")
	}
	return whichConn[0]
}

func EscapeConnectionParam(param string) string {
	param = strings.ReplaceAll(param, `\`, `\\`)
	param = strings.ReplaceAll(param, `'`, `\'`)
	return param
}

func MustSelectString(connection *DBConn, query string, whichConn ...int) string {
	str, err := SelectString(connection, query, whichConn...)
	gplog.FatalOnError(err)
	return str
}

// SelectString returns the single string column of a query that produces at
// most one row. An empty result set yields ("", nil); more than one row is an
// error. Use SelectStringSlice for multi-row queries.
func SelectString(connection *DBConn, query string, whichConn ...int) (string, error) {
	connNum := connection.ValidateConnNum(whichConn...)
	rows, err := connection.query(connNum, query)
	if err != nil {
		return "", err
	}
	defer rows.Close()
	if cols, _ := rows.Columns(); len(cols) > 1 {
		return "", fmt.Errorf("Too many columns returned from query: got %d columns, expected 1 column", len(cols))
	}
	if !rows.Next() {
		return "", rows.Err()
	}
	var result sql.NullString
	if err := rows.Scan(&result); err != nil {
		return "", err
	}
	if rows.Next() {
		return "", fmt.Errorf("Too many rows returned from query: expected at most 1 row")
	}
	return result.String, rows.Err()
}

func MustSelectStringSlice(connection *DBConn, query string, whichConn ...int) []string {
	str, err := SelectStringSlice(connection, query, whichConn...)
	gplog.FatalOnError(err)
	return str
}

func SelectStringSlice(connection *DBConn, query string, whichConn ...int) ([]string, error) {
	connNum := connection.ValidateConnNum(whichConn...)
	rows, err := connection.query(connNum, query)
	if err != nil {
		return []string{}, err
	}
	defer rows.Close()
	if cols, _ := rows.Columns(); len(cols) > 1 {
		return []string{}, fmt.Errorf("Too many columns returned from query: got %d columns, expected 1 column", len(cols))
	}
	retval := make([]string, 0)
	for rows.Next() {
		var result sql.NullString
		if err := rows.Scan(&result); err != nil {
			return []string{}, err
		}
		retval = append(retval, result.String)
	}
	if err := rows.Err(); err != nil {
		return []string{}, err
	}
	return retval, nil
}
