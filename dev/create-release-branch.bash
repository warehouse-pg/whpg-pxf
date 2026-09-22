#!/usr/bin/env bash

set -e

: "${GIT_REMOTE:=origin}" # if your remote is not called origin, you can override it here
: "${GOOGLE_PROJECT_ID:=data-gpdb-ud}" # if your GCP project is not data-gpdb-ud, you can override it here

GIT_SHA="${1}"
MAJOR_MINOR_VERSION="${2}"
SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )
pxf_src=$(dirname "${SCRIPT_DIR}")

function print_usage() {
  cat <<EOF
NAME
    create-release-branch.bash - create a release branch and add image tags

SYNOPSIS
    create-release-branch.bash <git sha> <major.minor version>
        <git sha>              commit from which to branch
        <major.minor version>  desired release version

DESCRIPTION
    When creating a new release, a new release branch is required and the version file
    within PXF must be updated to a SNAPSHOT version. For the release pipeline, this script
    will also tag any necessary images needed by the various jobs.

    For example:

        create-release-branch.bash abcdef 1.2

    would first ask for confirmation on creating the release branch. After which it would
    create a branch for the release called \`branch-1.2.x\` off commit \`abcdef\`.
    It would also update the version inside pxf/version to be \`1.2.0-SNAPSHOT\`.

    Then, it would ask for confirmation on tagging the images needed for the release branch.
    After confirmation, this script would update the necessary images with \`release-1.2.x\`
    and then print out the make command for setting the new release pipeline to use the newly
    created branch and the newly tagged images.
EOF
}

function check_pre_requisites() {
    if ! type gcloud &>/dev/null; then
        >&2 echo 'gcloud is not found, did you install and configure it?'
        >&2 echo 'See https://cloud.google.com/sdk/docs/install and https://cloud.google.com/sdk/docs/initializing'
        exit 1
    fi
}

function validate_version() {
  # validate version input
  if ! [[ "${MAJOR_MINOR_VERSION}" =~ ^[0-9]+\.[0-9]+$ ]]; then
    echo "ERROR: expected <major.minor> but received <${MAJOR_MINOR_VERSION}>."
    exit 1
  fi
}

function prompt_for_confirmation() {
  local message="${1}"
  local cmd_to_run="${2}"
  while true; do
    read -r -p "${message}" yn
    case $yn in
    [Yy]*)
      ${cmd_to_run}
      break ;;
    [Nn]*) break ;;
    *) echo "Please answer yes or no." ;;
    esac
  done
}

function create_branch_and_snapshot_commit() {
  echo "Creating new branch ${branch_name}..."
  git switch -c "${branch_name}" "${GIT_SHA}"

  # change version and add -SNAPSHOT
  local snapshot_version="${MAJOR_MINOR_VERSION}".0-SNAPSHOT

  echo "Changing version to ${snapshot_version} and committing change..."
  echo "${snapshot_version}" > "${pxf_src}"/version
  git add "${pxf_src}"/version
  git commit -m "Bump version to ${snapshot_version} [skip ci]"

  echo "Pushing new branch ${branch_name} and new SNAPSHOT version ${snapshot_version} to ${GIT_REMOTE}"
  git push -u "${GIT_REMOTE}" "${branch_name}"

  echo "Restoring the workspace to the previous branch ${current_branch}"
  git switch "${current_branch}"
}

function tag_current_latest() {
  local image_list=("$@")
  for image in "${image_list[@]}"; do
    # we need to untag ${image_tag} first if it already exists
    # this returns an empty value if no tags are found
    tag_exists=$(gcloud container images list-tags gcr.io/"${GOOGLE_PROJECT_ID}"/"${image}" --filter="tags : ${image_tag}")
    echo "Found existing tag \`${image_tag}\` for gcr.io/${GOOGLE_PROJECT_ID}/${image}"
    # if the call to gcloud returned values, make sure the tag is in the output
    if [[ -n "${tag_exists}" && "${tag_exists}" =~ ${image_tag}  ]]; then
      echo "Removing existing tag..."
      gcloud container images untag --quiet "gcr.io/${GOOGLE_PROJECT_ID}/${image}:${image_tag}" || true
    fi
    # tag latest image with ${image_tag}
    echo "Tagging gcr.io/${GOOGLE_PROJECT_ID}/${image}:latest with \`${image_tag}\`..."
    gcloud container images add-tag --quiet \
      "gcr.io/${GOOGLE_PROJECT_ID}/${image}:latest" \
      "gcr.io/${GOOGLE_PROJECT_ID}/${image}:${image_tag}"
  done
}

function tag_images() {
TEST_IMAGE_LIST=(
  'gpdb-pxf-dev/gpdb5-centos7-test-pxf'
  'gpdb-pxf-dev/gpdb6-centos7-test-pxf'
  'gpdb-pxf-dev/gpdb6-rocky8-test-pxf'
  'gpdb-pxf-dev/gpdb6-rocky9-test-pxf'
  'gpdb-pxf-dev/gpdb7-rocky8-test-pxf'
  'gpdb-pxf-dev/gpdb7-rocky9-test-pxf'
  'gpdb-pxf-dev/gpdb6-ubuntu18.04-test-pxf'
  'gpdb-pxf-dev/gpdb6-oel7-test-pxf'
)

RPMREBUILD_IMAGE_LIST=(
  'rpmrebuild-centos7'
  'rpmrebuild-rocky8'
  'rpmrebuild-rocky9'
)

  echo "Tagging current \`latest\` docker images with \`${image_tag}\`..."
  tag_current_latest "${TEST_IMAGE_LIST[@]}"
  tag_current_latest "${RPMREBUILD_IMAGE_LIST[@]}"
}

# --- main script logic ---

# do basic validation
if [ $# -lt 2 ]; then
  print_usage
  exit 1
fi
check_pre_requisites
validate_version

# get the version from the branch name
branch_name=branch-"${MAJOR_MINOR_VERSION}".x
image_tag=release-"${MAJOR_MINOR_VERSION}".x

current_branch=$(git rev-parse --abbrev-ref HEAD)
if [[ "${current_branch}" != "main" ]]; then
    echo "ERROR: Please create release branches off \`main\`."
    exit 1
fi

# double check that they want to create the branch
prompt_for_confirmation "Create branch \`${branch_name}\` on git remote \`${GIT_REMOTE}\` and push the initial SNAPSHOT commit (y/n)? " create_branch_and_snapshot_commit

# create the new tag for the branches
prompt_for_confirmation "Create docker tag \`${image_tag}\` for images to use with the release pipeline for branch \`${branch_name}\` (y/n)? " tag_images

echo "The Concourse release pipeline has been retired; releases are built and published"
echo "through the GitHub Actions workflows in whpg-extensions-packaging."
