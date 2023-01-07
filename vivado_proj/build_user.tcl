# Implementation
set app       [lindex $argv 0]
set user_conf [lindex $argv 1]
set shell_dir [lindex $argv 2]
set part      [lindex $argv 3]
set build_dir [file normalize "."]

set project   "${app}_$user_conf"

open_project "$build_dir/$project/$project.xpr"

# synthesis
update_compile_order
reset_run synth_1
launch_runs -verbose synth_1
wait_on_run synth_1
open_run synth_1
write_checkpoint "${build_dir}/${app}_${user_conf}_syn.dcp"
report_utilization -file "${build_dir}/${app}_${user_conf}_syn.rpt"
close_project

puts "**** User design synthesised."

# implementation with the abstract shell
set tcl_dir   [file normalize "[file dirname [info script]]"]
source -quiet "$tcl_dir/impl_proc.tcl"

create_project -in_memory -part $part
add_files "$build_dir/${app}_${user_conf}_syn.dcp"
add_files "$shell_dir/dcp/user_wrapper_abshell_0.dcp"

# map the syn user netlist to the greybox in the abstract shell
set_property SCOPED_TO_CELLS {inst_dynamic/inst_user_wrapper_0} [get_files "${app}_${user_conf}_syn.dcp"]
link_design -mode default -reconfig_partitions {inst_dynamic/inst_user_wrapper_0} -part $part -top cyt_top
implement_design
write_checkpoint -cell {inst_dynamic/inst_user_wrapper_0} "$build_dir/${app}_${user_conf}_routed.dcp"

report_utilization -file "$build_dir/${app}_${user_conf}_utilization.rpt"
report_timing_summary -file "$build_dir/${app}_${user_conf}_timing_summary.rpt"

write_bitstream -cell {inst_dynamic/inst_user_wrapper_0} "$build_dir/${app}_$user_conf.bit"
write_debug_probes -cell {inst_dynamic/inst_user_wrapper_0} "$build_dir/${app}_$user_conf.ltx"

close_project

puts "**** User design implemented with abstract shell."

# merge the partial user dcp with static_shell dcp

create_project -in_memory -part $part
add_files "$build_dir/${app}_${user_conf}_routed.dcp"
add_files "$shell_dir/dcp/static_shell.dcp"

# map the syn user netlist to the greybox in the abstract shell
set_property SCOPED_TO_CELLS {inst_dynamic/inst_user_wrapper_0} [get_files "${app}_${user_conf}_routed.dcp"]
link_design -mode default -reconfig_partitions {inst_dynamic/inst_user_wrapper_0} -part $part -top cyt_top
write_checkpoint "$build_dir/${app}_${user_conf}_full.dcp"

puts "**** User design and static shell linked."

write_bitstream "$build_dir/${app}_${user_conf}_full.bit"
write_debug_probes "$build_dir/${app}_${user_conf}_full.ltx"

puts "**** Full bitstream generated."

close_project