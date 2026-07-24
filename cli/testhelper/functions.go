package testhelper

import (
	"database/sql"
	"fmt"
	"os"
	"regexp"
	"strings"

	sqlmock "github.com/DATA-DOG/go-sqlmock"
	"pxf-cli/dbconn"
	"pxf-cli/gplog"
	"pxf-cli/operating"
	. "github.com/onsi/gomega"
	"github.com/onsi/gomega/gbytes"
)

func SetupTestLogger() (*gbytes.Buffer, *gbytes.Buffer, *gbytes.Buffer) {
	testStdout := gbytes.NewBuffer()
	testStderr := gbytes.NewBuffer()
	testLogfile := gbytes.NewBuffer()
	testLogger := gplog.NewLogger(testStdout, testStderr, testLogfile, "gbytes.Buffer", gplog.LOGINFO, "testProgram")
	gplog.SetLogger(testLogger)
	return testStdout, testStderr, testLogfile
}

func SetupTestEnvironment() (*dbconn.DBConn, sqlmock.Sqlmock, *gbytes.Buffer, *gbytes.Buffer, *gbytes.Buffer) {
	testStdout, testStderr, testLogfile := SetupTestLogger()
	connection, mock := CreateAndConnectMockDB(1)
	operating.System = operating.InitializeSystemFunctions()
	return connection, mock, testStdout, testStderr, testLogfile
}

func CreateMockDB() (*sql.DB, sqlmock.Sqlmock) {
	db, mock, err := sqlmock.New()
	Expect(err).To(BeNil(), "Could not create mock database connection")
	return db, mock
}

/*
 * SetDBVersion exists alongside dbconn.NewVersion to make `defer`-style version
 * swaps in tests less awkward:
 *   defer testhelper.SetDBVersion(conn, "5.0.0")
 * instead of an anonymous func wrapper.
 */
func SetDBVersion(connection *dbconn.DBConn, versionStr string) {
	connection.Version = dbconn.NewVersion(versionStr)
}

func CreateMockDBConn(errs ...error) (*dbconn.DBConn, sqlmock.Sqlmock) {
	mockdb, mock := CreateMockDB()
	driver := &TestDriver{DB: mockdb, DBName: "testdb", User: "testrole"}
	if len(errs) > 0 {
		driver.ErrsToReturn = errs
	}
	connection := dbconn.NewDBConnFromEnvironment("testdb")
	connection.Driver = driver
	connection.Host = "testhost"
	connection.Port = 5432
	return connection, mock
}

func ExpectVersionQuery(mock sqlmock.Sqlmock, versionStr string) {
	versionRow := sqlmock.NewRows([]string{"versionstring"}).AddRow(fmt.Sprintf("(Greenplum Database %s)", versionStr))
	mock.ExpectQuery(regexp.QuoteMeta("SELECT pg_catalog.version() AS versionstring")).WillReturnRows(versionRow)
}

func CreateAndConnectMockDB(numConns int) (*dbconn.DBConn, sqlmock.Sqlmock) {
	connection, mock := CreateMockDBConn()
	ExpectVersionQuery(mock, "5.1.0")
	connection.MustConnect(numConns)
	return connection, mock
}

func ExpectRegexp(buffer *gbytes.Buffer, testStr string) {
	Expect(buffer).Should(gbytes.Say(regexp.QuoteMeta(testStr)))
}

func NotExpectRegexp(buffer *gbytes.Buffer, testStr string) {
	Expect(buffer).ShouldNot(gbytes.Say(regexp.QuoteMeta(testStr)))
}

func ShouldPanicWithMessage(message string) {
	r := recover()
	Expect(r).NotTo(BeNil(), "Function did not panic as expected")

	errorMessage := strings.TrimSpace(fmt.Sprintf("%v", r))
	Expect(errorMessage).Should(ContainSubstring(message))
}

func AssertQueryRuns(connection *dbconn.DBConn, query string) {
	_, err := connection.Exec(query)
	Expect(err).To(BeNil(), "%s", query)
}

/*
 * After calling MockFileContents, restore operating.System with
 * InitializeSystemFunctions in a defer or AfterEach so the override
 * doesn't leak into other tests.
 */
func MockFileContents(contents string) {
	r, w, _ := os.Pipe()
	operating.System.OpenFileRead = func(name string, flag int, perm os.FileMode) (operating.ReadCloserAt, error) {
		return r, nil
	}
	go func() {
		_, _ = w.Write([]byte(contents))
		_ = w.Close()
	}()
}
