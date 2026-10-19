package org.greenplum.pxf.automation.components.common;

import jsystem.framework.system.SystemObjectImpl;

/**
 * Common System Objects layer. Every system Object needs to extend it.
 */
public abstract class BaseSystemObject extends SystemObjectImpl {

	public BaseSystemObject() {

	}

	/**
	 * Possibility to "manually" initialize system object (instead from sut file) and not fully work
	 * from the jsystem framework. In this case, there is an option to silent the jsystem reporter
	 * which can cause exceptions if not fully using the framework.
	 * 
	 * @param silentReport
	 */
	public BaseSystemObject(boolean silentReport) {
		if (report != null) {
			report.setSilent(silentReport);
		}
	}

	protected String replaceHome(String value) {
		if (value == null) {
			return null;
		}
		String home = System.getenv("HOME");
		return value.replace("${home}", home).replace("${HOME}", home);
	}

	protected String replaceUser(String value) {
		if (value == null) {
			return null;
		}
		String user = System.getenv("USER");
		return value.replace("${user}", user).replace("${USER}", user);
	}

	/**
	 * Expands ${ipa.domain} and ${ipa.realm} for the IPA cluster that
	 * dev/ipa-cluster.bash creates in the GCP project named by
	 * TF_VAR_gcp_project (or -Dipa.gcp.project): c.<project>.internal and
	 * its upper-cased Kerberos realm.
	 */
	protected String replaceIpa(String value) {
		if (value == null || !value.contains("${ipa.")) {
			return value;
		}
		String project = System.getProperty("ipa.gcp.project", System.getenv("TF_VAR_gcp_project"));
		if (project == null || project.isEmpty()) {
			throw new IllegalStateException("TF_VAR_gcp_project (or -Dipa.gcp.project) is required to resolve " + value);
		}
		String domain = "c." + project + ".internal";
		return value.replace("${ipa.domain}", domain).replace("${ipa.realm}", domain.toUpperCase());
	}
}
