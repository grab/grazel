#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/../.." && pwd)"

ruby - "$repo_root" "$@" <<'RUBY'
require "json"
require "fileutils"
require "open3"

repo_root = ARGV.shift

options = {
  "mode" => "preserving",
  "pax_root" => ENV.fetch("PAX_ROOT", "/Users/arun.sampathkumar/work/pax-android"),
  "baseline" => File.join(repo_root, "reports/specs/pax-size-baseline.json"),
  "write_baseline" => false
}

until ARGV.empty?
  arg = ARGV.shift
  case arg
  when "--mode"
    options["mode"] = ARGV.shift or abort "Missing value for --mode"
  when "--pax-root"
    options["pax_root"] = ARGV.shift or abort "Missing value for --pax-root"
  when "--baseline"
    options["baseline"] = ARGV.shift or abort "Missing value for --baseline"
  when "--write-baseline"
    options["write_baseline"] = true
  when "--help", "-h"
    puts <<~USAGE
      Usage: reports/scripts/verify-pax-size-guard.sh [options]

      Options:
        --mode preserving|item13     Comparison mode. Default: preserving.
        --pax-root PATH              PAX checkout. Default: ENV[PAX_ROOT] or /Users/arun.sampathkumar/work/pax-android.
        --baseline PATH              Baseline JSON path. Default: reports/specs/pax-size-baseline.json.
        --write-baseline             Write the current PAX generated shape as the baseline.
    USAGE
    exit 0
  else
    abort "Unknown argument: #{arg}"
  end
end

unless %w[preserving item13].include?(options["mode"])
  abort "Unsupported --mode #{options["mode"].inspect}; expected preserving or item13"
end

def run_git(root, *args)
  stdout, stderr, status = Open3.capture3("git", "-C", root, *args)
  return stdout.strip if status.success?

  warn stderr unless stderr.empty?
  ""
end

def repo_install_json(repo)
  repo == "maven" ? "maven_install.json" : "#{repo}_install.json"
end

def parse_maven_install_repos(workspace)
  repos = []
  lines = workspace.lines
  index = 0

  while index < lines.length
    line = lines[index]
    unless line.match?(/^\s*maven_install\(/)
      index += 1
      next
    end

    block = []
    until index >= lines.length
      block << lines[index]
      break if lines[index].match?(/^\s*\)\s*$/)
      index += 1
    end

    name = block.join.match(/^\s*name\s*=\s*"([^"]+)"/)&.[](1)
    abort "Found maven_install block without name" unless name

    repos << name
    index += 1
  end

  abort "No active maven_install blocks found in WORKSPACE" if repos.empty?
  repos.sort
end

def artifact_identity_from_pin_json(path)
  data = JSON.parse(File.read(path))
  input_hash = data["__INPUT_ARTIFACTS_HASH"]
  unless input_hash.is_a?(Hash)
    abort "#{path} is missing __INPUT_ARTIFACTS_HASH; cannot verify materialized maven_install roots"
  end

  input_hash
    .map { |artifact, hash| "#{artifact}=#{hash}" }
    .sort
end

def build_snapshot(pax_root)
  workspace_path = File.join(pax_root, "WORKSPACE")
  abort "Missing #{workspace_path}. Run PAX migrateToBazel first." unless File.file?(workspace_path)

  repos = parse_maven_install_repos(File.read(workspace_path))
  missing_json = repos
    .map { |repo| File.join(pax_root, repo_install_json(repo)) }
    .reject { |path| File.file?(path) }
  unless missing_json.empty?
    abort "Missing active pin JSON files:\n#{missing_json.sort.map { |path| "  - #{path}" }.join("\n")}"
  end

  per_repo = repos.to_h do |repo|
    artifact_ids = artifact_identity_from_pin_json(File.join(pax_root, repo_install_json(repo)))
    [repo, {
      "artifactRoots" => artifact_ids.length,
      "artifactIds" => artifact_ids
    }]
  end

  {
    "paxRepoPath" => pax_root,
    "paxBranch" => run_git(pax_root, "branch", "--show-current"),
    "paxCommit" => run_git(pax_root, "rev-parse", "HEAD"),
    "bucketCount" => per_repo.length,
    "pinfileCount" => per_repo.length,
    "totalArtifactRoots" => per_repo.values.sum { |repo| repo.fetch("artifactRoots") },
    "perRepo" => per_repo
  }
end

def validate_baseline!(baseline)
  top_level = %w[paxRepoPath paxBranch paxCommit bucketCount pinfileCount totalArtifactRoots perRepo]
  missing = top_level.reject { |key| baseline.key?(key) }
  abort "Baseline is missing required fields: #{missing.join(", ")}" unless missing.empty?

  %w[bucketCount pinfileCount totalArtifactRoots].each do |key|
    abort "Baseline field #{key} must be an integer" unless baseline[key].is_a?(Integer)
  end

  abort "Baseline field perRepo must be an object" unless baseline["perRepo"].is_a?(Hash)

  baseline["perRepo"].each do |repo, data|
    unless data.is_a?(Hash) && data["artifactRoots"].is_a?(Integer) && data["artifactIds"].is_a?(Array)
      abort "Baseline repo #{repo} must contain artifactRoots integer and artifactIds array"
    end
    unless data["artifactIds"].all? { |artifact| artifact.is_a?(String) }
      abort "Baseline repo #{repo} artifactIds must all be strings"
    end
    sorted = data["artifactIds"].sort
    abort "Baseline repo #{repo} artifactIds must be sorted" unless data["artifactIds"] == sorted
    unless data["artifactRoots"] == data["artifactIds"].length
      abort "Baseline repo #{repo} artifactRoots does not match artifactIds length"
    end
  end
end

def scoped_item13_repo?(repo)
  repo.match?(/(^|_)test(_|$)/) || repo.include?("android_test")
end

def compare_totals!(baseline, current)
  failures = []
  %w[bucketCount pinfileCount totalArtifactRoots].each do |key|
    if current[key] > baseline[key]
      failures << "#{key} increased: baseline=#{baseline[key]}, current=#{current[key]}"
    end
  end
  failures
end

def compare_preserving!(baseline, current)
  failures = []
  baseline_repos = baseline["perRepo"].keys.sort
  current_repos = current["perRepo"].keys.sort

  missing_repos = baseline_repos - current_repos
  added_repos = current_repos - baseline_repos
  failures << "Missing repos: #{missing_repos.join(", ")}" unless missing_repos.empty?
  failures << "Added repos: #{added_repos.join(", ")}" unless added_repos.empty?

  (baseline_repos & current_repos).each do |repo|
    expected = baseline["perRepo"][repo]["artifactIds"]
    actual = current["perRepo"][repo]["artifactIds"]
    next if expected == actual

    removed = expected - actual
    added = actual - expected
    failures << "#{repo} artifact identity changed: removed=#{removed.length}, added=#{added.length}"
  end

  failures
end

def compare_item13!(baseline, current)
  failures = []
  baseline_repos = baseline["perRepo"].keys.sort
  current_repos = current["perRepo"].keys.sort
  all_repos = (baseline_repos | current_repos).sort

  scoped_baseline_total = 0
  scoped_current_total = 0

  all_repos.each do |repo|
    expected = baseline.dig("perRepo", repo, "artifactIds") || []
    actual = current.dig("perRepo", repo, "artifactIds") || []

    if scoped_item13_repo?(repo)
      scoped_baseline_total += expected.length
      scoped_current_total += actual.length
      next
    end

    if expected.empty? && !actual.empty?
      failures << "Non-scoped repo added in item13 mode: #{repo}"
    elsif !expected.empty? && actual.empty?
      failures << "Non-scoped repo removed in item13 mode: #{repo}"
    elsif expected != actual
      failures << "Non-scoped repo changed in item13 mode: #{repo}"
    end
  end

  if scoped_current_total > scoped_baseline_total
    failures << "Scoped test/androidTest artifact roots increased: baseline=#{scoped_baseline_total}, current=#{scoped_current_total}"
  end

  failures
end

def print_summary(baseline, current, mode)
  puts "PAX size guard mode: #{mode}"
  puts "PAX root: #{current["paxRepoPath"]}"
  puts "PAX branch/SHA: #{current["paxBranch"]} #{current["paxCommit"]}"
  puts

  %w[bucketCount pinfileCount totalArtifactRoots].each do |key|
    delta = current[key] - baseline[key]
    status = if delta < 0
      "reduction #{delta}"
    elsif delta.zero?
      "unchanged"
    else
      "increase +#{delta}"
    end
    puts "- #{key}: baseline=#{baseline[key]}, current=#{current[key]} (#{status})"
  end

  puts
  puts "Per-repo artifact root deltas:"
  all_repos = (baseline["perRepo"].keys | current["perRepo"].keys).sort
  all_repos.each do |repo|
    expected = baseline.dig("perRepo", repo, "artifactRoots") || 0
    actual = current.dig("perRepo", repo, "artifactRoots") || 0
    delta = actual - expected
    next if delta.zero?
    puts "- #{repo}: baseline=#{expected}, current=#{actual}, delta=#{delta > 0 ? "+#{delta}" : delta}"
  end
  puts "- none" if all_repos.all? { |repo| (current.dig("perRepo", repo, "artifactRoots") || 0) == (baseline.dig("perRepo", repo, "artifactRoots") || 0) }
end

current = build_snapshot(options["pax_root"])

if options["write_baseline"]
  FileUtils.mkdir_p(File.dirname(options["baseline"]))
  File.write(options["baseline"], JSON.pretty_generate(current) + "\n")
  puts "Wrote PAX size baseline to #{options["baseline"]}"
  puts "bucketCount=#{current["bucketCount"]}, pinfileCount=#{current["pinfileCount"]}, totalArtifactRoots=#{current["totalArtifactRoots"]}"
  exit 0
end

abort "Missing baseline #{options["baseline"]}; run with --write-baseline first." unless File.file?(options["baseline"])

baseline = JSON.parse(File.read(options["baseline"]))
validate_baseline!(baseline)

failures = []
failures.concat(compare_totals!(baseline, current))
failures.concat(
  if options["mode"] == "item13"
    compare_item13!(baseline, current)
  else
    compare_preserving!(baseline, current)
  end
)

print_summary(baseline, current, options["mode"])

unless failures.empty?
  warn
  warn "PAX size guard failed:"
  failures.each { |failure| warn "- #{failure}" }
  exit 1
end

puts
puts "PAX size guard passed."
RUBY
