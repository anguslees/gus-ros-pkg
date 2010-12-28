cmake_minimum_required(VERSION 2.4.6)
include($ENV{ROS_ROOT}/core/rosbuild/rosbuild.cmake)
set(ROS_BUILD_TYPE Debug)
rosbuild_init()

# This is required to break a circular dependency between our deps
# (roslib msgs) and our own rosjava source.  Other Java packages won't
# need to do this.
add_classpath(${PROJECT_SOURCE_DIR}/src)

add_java_source_dir(${PROJECT_SOURCE_DIR}/src )

set(LIBRARY_OUTPUT_PATH ${PROJECT_SOURCE_DIR}/bin)
