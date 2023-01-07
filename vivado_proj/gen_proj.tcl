# General user top project

set app       [lindex $argv 0]
set user_conf [lindex $argv 1]
set part      [lindex $argv 2]
set ip_dir    [lindex $argv 3]


puts "Generating ${app} project with config $user_conf"
set project         "${app}_$user_conf"
set top_module      "${app}_$user_conf"

# Source files are included relative to the directory containing this script.
set src_dir   [file normalize "[file dirname [info script]]/../"]
set build_dir [file normalize "."]

# Create project (force)
create_project -force $project "$build_dir/$project" -part $part
set proj [current_project]

# Set project properties
set_property "default_lib" "xil_defaultlib"                                  $proj
set_property "ip_cache_permissions" "read write"                             $proj
set_property "ip_output_repo" "$build_dir/$project/$project.cache/ip"        $proj
set_property "sim.ip.auto_export_scripts" "1"                                $proj
set_property "simulator_language" "Mixed"                                    $proj
set_property "xpm_libraries" "XPM_CDC XPM_MEMORY"                            $proj
set_property "ip_repo_paths" "$ip_dir"                                       $proj
set_property -name {STEPS.SYNTH_DESIGN.ARGS.MORE OPTIONS} -value {-mode out_of_context} -objects [get_runs synth_1]

# Make sure any repository IP is visible.
update_ip_catalog

add_files -fileset [get_filesets sources_1] "$src_dir/generated_rtl/${app}_$user_conf.v"

# xdc
# set_property used_in_implementation false  [get_files -of_objects [get_filesets constrs_1]]

# Set top entity
set_property "top" $top_module [get_filesets sources_1]

# Create a project-local constraint file to take debugging constraints that we
# don't want to propagate to the repository.
file mkdir "$build_dir/$project/$project.srcs/constrs_1"
close [ open "$build_dir/$project/$project.srcs/constrs_1/local.xdc" w ]

puts "**** User design project generated."

update_compile_order
reset_run synth_1
launch_runs -verbose synth_1
wait_on_run synth_1
open_run synth_1
write_checkpoint "${build_dir}/${app}_${user_conf}_syn.dcp"
report_utilization -file "${build_dir}/${app}_${user_conf}_syn.rpt"

# Temp: will be move to build_user.tcl later
puts "**** User design synthesised."

close_project




