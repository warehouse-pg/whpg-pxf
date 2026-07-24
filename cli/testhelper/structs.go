package testhelper

import (
	"context"
	"database/sql"
	"time"

	"pxf-cli/cluster"
	"pxf-cli/gplog"
)

type TestDriver struct {
	ErrToReturn  error
	ErrsToReturn []error
	DB           *sql.DB
	DBName       string
	User         string
	CallNumber   int
}

func (driver *TestDriver) Connect(driverName string, dataSourceName string) (*sql.DB, error) {
	if driver.ErrsToReturn != nil && driver.CallNumber < len(driver.ErrsToReturn) {
		err := driver.ErrsToReturn[driver.CallNumber]
		driver.CallNumber++
		return nil, err
	} else if driver.ErrToReturn != nil {
		return nil, driver.ErrToReturn
	}
	return driver.DB, nil
}

type TestResult struct {
	Rows int64
}

func (result TestResult) LastInsertId() (int64, error) {
	return 0, nil
}

func (result TestResult) RowsAffected() (int64, error) {
	return result.Rows, nil
}

/*
 * TestExecutor: a recording mock that satisfies cluster.Executor. Each output
 * channel (local / cluster, error / value) has both a plural and a singular
 * form: if the plural is set, it overrides the singular and one element is
 * consumed per call. Running out of plural entries triggers Fatal unless
 * UseLastOutput or UseDefaultOutput is set.
 *
 * LocalOutputs and LocalErrors are length-coupled (one of each per call), even
 * when UseLastOutput / UseDefaultOutput is set, so the two slices must always
 * have the same length.
 */
type TestExecutor struct {
	LocalOutput   string
	LocalOutputs  []string
	LocalError    error
	LocalErrors   []error
	LocalCommands []string
	LocalContexts []context.Context

	ClusterOutput   *cluster.RemoteOutput
	ClusterOutputs  []*cluster.RemoteOutput
	ClusterCommands [][]cluster.ShellCommand

	ErrorOnExecNum       int
	NumExecutions        int
	NumLocalExecutions   int
	NumClusterExecutions int
	UseLastOutput        bool
	UseDefaultOutput     bool
}

func (executor *TestExecutor) ExecuteLocalCommand(commandStr string) (string, error) {
	executor.NumExecutions++
	executor.NumLocalExecutions++
	executor.LocalCommands = append(executor.LocalCommands, commandStr)
	if (executor.LocalOutputs == nil && executor.LocalErrors != nil) || (executor.LocalOutputs != nil && executor.LocalErrors == nil) {
		gplog.Fatal(nil, "If one of LocalOutputs or LocalErrors is set, both must be set")
	} else if executor.LocalOutputs != nil && executor.LocalErrors != nil && len(executor.LocalOutputs) != len(executor.LocalErrors) {
		gplog.Fatal(nil, "Found %d LocalOutputs and %d LocalErrors, but one output and one error must be set for each call", len(executor.LocalOutputs), len(executor.LocalErrors))
	}
	if executor.LocalOutputs != nil {
		if executor.NumLocalExecutions <= len(executor.LocalOutputs) {
			return executor.LocalOutputs[executor.NumLocalExecutions-1], executor.LocalErrors[executor.NumLocalExecutions-1]
		} else if executor.UseLastOutput {
			return executor.LocalOutputs[len(executor.LocalOutputs)-1], executor.LocalErrors[len(executor.LocalErrors)-1]
		} else if executor.UseDefaultOutput {
			return executor.LocalOutput, executor.LocalError
		}
		gplog.Fatal(nil, "ExecuteLocalCommand called %d times, but only %d outputs and errors provided", executor.NumLocalExecutions, len(executor.LocalOutputs))
	} else if executor.ErrorOnExecNum == 0 || executor.NumLocalExecutions == executor.ErrorOnExecNum {
		return executor.LocalOutput, executor.LocalError
	}
	return executor.LocalOutput, nil
}

func (executor *TestExecutor) ExecuteLocalCommandWithContext(commandStr string, ctx context.Context) (string, error) {
	executor.NumExecutions++
	executor.NumLocalExecutions++
	executor.LocalCommands = append(executor.LocalCommands, commandStr)
	executor.LocalContexts = append(executor.LocalContexts, ctx)
	if (executor.LocalOutputs == nil && executor.LocalErrors != nil) || (executor.LocalOutputs != nil && executor.LocalErrors == nil) {
		gplog.Fatal(nil, "If one of LocalOutputs or LocalErrors is set, both must be set")
	} else if executor.LocalOutputs != nil && executor.LocalErrors != nil && len(executor.LocalOutputs) != len(executor.LocalErrors) {
		gplog.Fatal(nil, "Found %d LocalOutputs and %d LocalErrors, but one output and one error must be set for each call", len(executor.LocalOutputs), len(executor.LocalErrors))
	}
	if executor.LocalOutputs != nil {
		if executor.NumLocalExecutions <= len(executor.LocalOutputs) {
			return executor.LocalOutputs[executor.NumLocalExecutions-1], executor.LocalErrors[executor.NumLocalExecutions-1]
		} else if executor.UseLastOutput {
			return executor.LocalOutputs[len(executor.LocalOutputs)-1], executor.LocalErrors[len(executor.LocalErrors)-1]
		} else if executor.UseDefaultOutput {
			return executor.LocalOutput, executor.LocalError
		}
		gplog.Fatal(nil, "ExecuteLocalCommandWithContext called %d times, but only %d outputs and errors provided", executor.NumLocalExecutions, len(executor.LocalOutputs))
	} else if executor.ErrorOnExecNum == 0 || executor.NumLocalExecutions == executor.ErrorOnExecNum {
		return executor.LocalOutput, executor.LocalError
	}
	return executor.LocalOutput, nil
}

func (executor *TestExecutor) ExecuteClusterCommand(scope cluster.Scope, commandList []cluster.ShellCommand) *cluster.RemoteOutput {
	executor.NumExecutions++
	executor.NumClusterExecutions++
	executor.ClusterCommands = append(executor.ClusterCommands, commandList)
	if executor.ClusterOutputs != nil {
		if executor.NumClusterExecutions <= len(executor.ClusterOutputs) {
			return executor.ClusterOutputs[executor.NumClusterExecutions-1]
		} else if executor.UseLastOutput {
			return executor.ClusterOutputs[len(executor.ClusterOutputs)-1]
		} else if executor.UseDefaultOutput {
			return executor.ClusterOutput
		}
		gplog.Fatal(nil, "ExecuteClusterCommand called %d times, but only %d ClusterOutputs provided", executor.NumClusterExecutions, len(executor.ClusterOutputs))
	}
	return executor.ClusterOutput
}

func (executor *TestExecutor) ExecuteClusterCommandWithRetries(scope cluster.Scope, commandList []cluster.ShellCommand, maxAttempts int, retrySleep time.Duration) *cluster.RemoteOutput {
	executor.NumExecutions++
	executor.NumClusterExecutions++
	executor.ClusterCommands = append(executor.ClusterCommands, commandList)
	if executor.ClusterOutputs != nil {
		if executor.NumClusterExecutions <= len(executor.ClusterOutputs) {
			return executor.ClusterOutputs[executor.NumClusterExecutions-1]
		} else if executor.UseLastOutput {
			return executor.ClusterOutputs[len(executor.ClusterOutputs)-1]
		} else if executor.UseDefaultOutput {
			return executor.ClusterOutput
		}
		gplog.Fatal(nil, "ExecuteClusterCommand called %d times, but only %d ClusterOutputs provided", executor.NumClusterExecutions, len(executor.ClusterOutputs))
	}
	return executor.ClusterOutput
}
