ROOT = .

BUILDVER=$(shell cat product.version)

BUILDNUM = $(BUILD_NUMBER)
ifeq ($(BUILDNUM),)
  BUILDNUM = $(shell whoami)
endif

# HADOOP_VERSION is stylistic (only one Apache build line is supported);
# HADOOP_DISTRO MUST be Apache — the CDH and HDP build paths were retired.
# Any other value falls through to a dispatch error below.
HADOOP_VERSION = undefined
HADOOP_DISTRO = Apache

TARGET = singlecluster-$(HADOOP_DISTRO).tar.gz

BUILDROOT = $(TARGET:%.tar.gz=%)
BINROOT = $(ROOT)/bin
TARSROOT = $(ROOT)/tars
TEMPLATESROOT = $(ROOT)/templates

VERSIONSFILE = $(BUILDROOT)/versions.txt

BINFILES = $(filter-out *~, $(wildcard $(BINROOT)/*))
TARFILES = $(subst $(TARSROOT)/,,$(wildcard $(TARSROOT)/*.tar.gz))
EXTRACTEDTARS = $(TARFILES:%.tar.gz=%.extracted)
TEMPLATES := $(shell find $(TEMPLATESROOT) -type f -not -iname "*~")
STACK = $(shell echo $(HADOOP_DISTRO) | tr A-Z a-z)

ALLTARGETS = singlecluster-*
DIRT = *.extracted *~

# Do not run this build script in parallel
.NOTPARALLEL:

.PHONY: all
all: clean $(TARGET)

.PHONY: clean
clean:
	-rm -rf $(ALLTARGETS)
	-rm -rf $(DIRT)

$(TARGET): $(BUILDROOT) make_tarball

$(BUILDROOT): copy_binfiles create_versions_file extract_products copy_templates copy_deps
	chmod -R +w $(BUILDROOT)

.PHONY: copy_binfiles
copy_binfiles: $(BINFILES)
	mkdir -p $(BUILDROOT)/bin
	cp $^ $(BUILDROOT)/bin

.PHONY: create_versions_file
create_versions_file:
	echo build number: $(BUILDNUM) > $(VERSIONSFILE)
	echo single_cluster-$(BUILDVER) >> $(VERSIONSFILE)

# extract_products:
#   1. Run the %.extracted pattern rule for every tars/*.tar.gz (extracts
#      each into BUILDROOT/<vendor-named>/).
#   2. Run the per-stack extract_stack_$(STACK) target for any post-extract
#      cleanup the stack requires (Apache: special-case directory renames
#      that the generic regex below can't handle).
#   3. Apply the generic rename loop to collapse <name>-<version>/ into
#      <name>/. The regex captures the first alpha-numeric run before the
#      first dash-digit:
#         hadoop-3.3.6/   -> hadoop/
#         hbase-2.6.5/    -> hbase/
#      Apache tarballs with the "apache-" prefix or "-bin" suffix are
#      handled by extract_stack_apache BEFORE this rename loop runs, so
#      they're already at canonical names.
.PHONY: extract_products
extract_products: $(EXTRACTEDTARS) extract_stack_$(STACK)
	for X in $(BUILDROOT)/*-[0-9]*; do \
		mv $$X `echo $$X | sed -e 's/^\($(BUILDROOT)\/[A-Za-z0-9]*\).*$$/\1/'`; \
	done;
	chmod -R +w $(BUILDROOT)
	du -sh $(BUILDROOT)/*

# extract_stack_apache:
#   Vanilla Apache tarballs land with a few directory-name shapes that the
#   generic regex in extract_products can't canonicalize on its own:
#     - apache-zookeeper-3.8.6-bin/  -> would become apache/ (BAD)
#     - apache-hive-2.3.8-bin/       -> would become apache/ (BAD; also collides)
#   Hadoop's hadoop-3.3.6/ and HBase's hbase-2.6.5/ rename fine with the
#   generic regex, no explicit handling needed.
#
#   This target also appends the actual extracted directory names to
#   versions.txt before they get renamed, so a future maintainer can read
#   the exact upstream version baked into the tarball.
.PHONY: extract_stack_apache
extract_stack_apache:
	find $(BUILDROOT) -maxdepth 1 -type d \( \
		-name "apache-zookeeper-*" -o \
		-name "apache-hive-*" -o \
		-name "hadoop-[0-9]*" -o \
		-name "hbase-[0-9]*" \
	\) -exec basename {} \; >> $(VERSIONSFILE)
	if [ -d "$(BUILDROOT)" ]; then \
		for d in $(BUILDROOT)/apache-zookeeper-*; do \
			[ -e "$$d" ] && mv "$$d" "$(BUILDROOT)/zookeeper"; \
		done; \
		for d in $(BUILDROOT)/apache-hive-*; do \
			[ -e "$$d" ] && mv "$$d" "$(BUILDROOT)/hive"; \
		done; \
	fi
	rm -rf $(BUILDROOT)/hbase*/docs $(BUILDROOT)/hadoop*/share/doc

.PHONY: copy_templates
copy_templates: $(TEMPLATES)
	for X in `ls $(BUILDROOT)`; do \
		if [ -d "$(TEMPLATESROOT)/$$X" ]; \
			then cp -r $(TEMPLATESROOT)/$$X/* $(BUILDROOT)/$$X; \
		fi; \
	done;
	cp -r $(TEMPLATESROOT)/conf $(BUILDROOT)

	-find $(BUILDROOT) -iname "*~" | xargs rm -f

.PHONY: copy_deps
copy_deps:
	find . -maxdepth 1 -name *.tar.gz | xargs -I {} tar xzf {} -C $(BUILDROOT)

.PHONY: make_tarball
make_tarball: $(BUILDROOT)
	tar czf $(BUILDROOT).tar.gz $<

%.extracted: $(TARSROOT)/%.tar.gz
	tar xzf $^ -C $(BUILDROOT)
	touch $@
	echo $* >> $(VERSIONSFILE)
