package gplog_test

import (
	"errors"
	"os/user"
	"testing"
	"time"

	"pxf-cli/gplog"
	"pxf-cli/operating"
	"pxf-cli/testhelper"

	. "github.com/onsi/ginkgo/v2"
	. "github.com/onsi/gomega"
	"github.com/onsi/gomega/gbytes"
)

func TestGpLog(t *testing.T) {
	RegisterFailHandler(Fail)
	RunSpecs(t, "gplog tests")
}

// contents reads the whole buffer without disturbing gbytes' read cursor.
func contents(b *gbytes.Buffer) string { return string(b.Contents()) }

var _ = Describe("gplog", func() {
	BeforeEach(func() {
		// Deterministic time/user/host/pid so header and prefix are exact.
		operating.System.Now = func() time.Time { return time.Date(2017, 1, 2, 3, 4, 5, 6, time.UTC) }
		operating.System.CurrentUser = func() (*user.User, error) {
			return &user.User{Username: "testUser", HomeDir: "testDir"}, nil
		}
		operating.System.Hostname = func() (string, error) { return "testHost", nil }
		operating.System.Getpid = func() int { return 7 }
		gplog.SetErrorCode(0)
	})
	AfterEach(func() {
		operating.System = operating.InitializeSystemFunctions()
	})

	Describe("verbosity get/set", func() {
		It("round-trips shell verbosity", func() {
			testhelper.SetupTestLogger()
			gplog.SetVerbosity(gplog.LOGDEBUG)
			Expect(gplog.GetVerbosity()).To(Equal(gplog.LOGDEBUG))
			gplog.SetVerbosity(gplog.LOGERROR)
			Expect(gplog.GetVerbosity()).To(Equal(gplog.LOGERROR))
		})
		It("round-trips log-file verbosity", func() {
			testhelper.SetupTestLogger()
			gplog.SetLogFileVerbosity(gplog.LOGVERBOSE)
			Expect(gplog.GetLogFileVerbosity()).To(Equal(gplog.LOGVERBOSE))
		})
		It("defaults a new logger to LOGINFO shell and LOGDEBUG file verbosity", func() {
			testhelper.SetupTestLogger()
			Expect(gplog.GetVerbosity()).To(Equal(gplog.LOGINFO))
			Expect(gplog.GetLogFileVerbosity()).To(Equal(gplog.LOGDEBUG))
		})
	})

	Describe("error code", func() {
		It("defaults to 0 and round-trips", func() {
			testhelper.SetupTestLogger()
			Expect(gplog.GetErrorCode()).To(Equal(0))
			gplog.SetErrorCode(1)
			Expect(gplog.GetErrorCode()).To(Equal(1))
		})
	})

	Describe("header and prefix formatting", func() {
		It("builds the header from program/user/host/pid", func() {
			Expect(gplog.GetHeader("myProgram")).To(Equal("myProgram:testUser:testHost:000007-[%s]:-"))
		})
		It("prepends the formatted timestamp and level to the header", func() {
			testhelper.SetupTestLogger()
			Expect(gplog.GetLogPrefix("INFO")).To(Equal("20170102:03:04:05 testProgram:testUser:testHost:000007-[INFO]:-"))
		})
		It("uses the same prefix for shell and file", func() {
			testhelper.SetupTestLogger()
			Expect(gplog.GetShellLogPrefix("ERROR")).To(Equal(gplog.GetLogPrefix("ERROR")))
		})
	})

	Describe("GenerateLogFileName", func() {
		It("names the file <logdir>/<program>_<YYYYMMDD>.log", func() {
			Expect(gplog.GenerateLogFileName("gpbackup", "/tmp/logs")).To(Equal("/tmp/logs/gpbackup_20170102.log"))
		})
	})

	Describe("GetLogFilePath", func() {
		It("returns the log file name given to the logger", func() {
			testhelper.SetupTestLogger()
			Expect(gplog.GetLogFilePath()).To(Equal("gbytes.Buffer"))
		})
	})

	Describe("Info", func() {
		It("writes to stdout and the log file, not stderr, at LOGINFO", func() {
			stdout, stderr, logfile := testhelper.SetupTestLogger()
			gplog.Info("hello %s", "world")
			Expect(contents(stdout)).To(ContainSubstring("[INFO]:-hello world"))
			Expect(contents(logfile)).To(ContainSubstring("[INFO]:-hello world"))
			Expect(contents(stderr)).To(BeEmpty())
		})
	})

	Describe("Warn", func() {
		It("always writes to stdout and the log file", func() {
			stdout, _, logfile := testhelper.SetupTestLogger()
			gplog.SetVerbosity(gplog.LOGERROR)
			gplog.Warn("careful")
			Expect(contents(stdout)).To(ContainSubstring("[WARNING]:-careful"))
			Expect(contents(logfile)).To(ContainSubstring("[WARNING]:-careful"))
		})
	})

	Describe("Error", func() {
		It("writes to stderr and the log file and sets the error code to 1", func() {
			_, stderr, logfile := testhelper.SetupTestLogger()
			gplog.Error("boom %d", 42)
			Expect(contents(stderr)).To(ContainSubstring("[ERROR]:-boom 42"))
			Expect(contents(logfile)).To(ContainSubstring("[ERROR]:-boom 42"))
			Expect(gplog.GetErrorCode()).To(Equal(1))
		})
	})

	Describe("Verbose and Debug gating", func() {
		It("logs to the file but not stdout at LOGINFO", func() {
			stdout, _, logfile := testhelper.SetupTestLogger() // shell LOGINFO, file LOGDEBUG
			gplog.Verbose("verbose msg")
			gplog.Debug("debug msg")
			Expect(contents(logfile)).To(ContainSubstring("verbose msg"))
			Expect(contents(logfile)).To(ContainSubstring("debug msg"))
			Expect(contents(stdout)).To(BeEmpty())
		})
		It("logs Verbose to stdout once shell verbosity is raised", func() {
			stdout, _, _ := testhelper.SetupTestLogger()
			gplog.SetVerbosity(gplog.LOGVERBOSE)
			gplog.Verbose("now visible")
			Expect(contents(stdout)).To(ContainSubstring("[DEBUG]:-now visible"))
		})
		It("suppresses even the file entry when file verbosity is lowered", func() {
			_, _, logfile := testhelper.SetupTestLogger()
			gplog.SetLogFileVerbosity(gplog.LOGINFO)
			gplog.Debug("hidden")
			Expect(contents(logfile)).ToNot(ContainSubstring("hidden"))
		})
	})

	Describe("Custom", func() {
		It("routes a LOGERROR shell verbosity to stderr regardless of shell verbosity", func() {
			_, stderr, logfile := testhelper.SetupTestLogger()
			gplog.SetVerbosity(gplog.LOGERROR)
			gplog.Custom(gplog.LOGINFO, gplog.LOGERROR, "custom err")
			Expect(contents(stderr)).To(ContainSubstring("[ERROR]:-custom err"))
			Expect(contents(logfile)).To(ContainSubstring("custom err"))
		})
	})

	Describe("Fatal", func() {
		It("panics with the message, sets the error code to 2, and records the message plus a stack trace in the log file", func() {
			_, _, logfile := testhelper.SetupTestLogger()
			gplog.SetVerbosity(gplog.LOGDEBUG)
			// Fatal writes the message to the log file and carries the shell message
			// in the panic value; it does not write to the stderr buffer directly.
			Expect(func() { gplog.Fatal(errors.New("the error"), "context %s", "here") }).To(
				PanicWith(ContainSubstring("[CRITICAL]:-the error: context here")))
			Expect(gplog.GetErrorCode()).To(Equal(2))
			Expect(contents(logfile)).To(ContainSubstring("[CRITICAL]:-the error: context here"))
			// captureStack/formatStackTrace: the file entry carries a stack trace.
			Expect(contents(logfile)).To(MatchRegexp(`\n\S+\n\t\S+\.go:\d+`))
		})
		It("handles a nil error, logging only the provided message", func() {
			_, _, logfile := testhelper.SetupTestLogger()
			Expect(func() { gplog.Fatal(nil, "just a message") }).To(Panic())
			Expect(contents(logfile)).To(ContainSubstring("[CRITICAL]:-just a message"))
		})
	})

	Describe("FatalOnError", func() {
		It("does nothing when err is nil", func() {
			testhelper.SetupTestLogger()
			Expect(func() { gplog.FatalOnError(nil) }).ToNot(Panic())
			Expect(gplog.GetErrorCode()).To(Equal(0))
		})
		It("panics when err is non-nil", func() {
			testhelper.SetupTestLogger()
			Expect(func() { gplog.FatalOnError(errors.New("bad")) }).To(Panic())
			Expect(gplog.GetErrorCode()).To(Equal(2))
		})
	})
})
