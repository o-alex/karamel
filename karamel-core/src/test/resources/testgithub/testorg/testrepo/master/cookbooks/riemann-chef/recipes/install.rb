#
# DO NOT EDIT THIS FILE DIRECTLY - UNLESS YOU KNOW WHAT YOU ARE DOING
#

include_recipe 'java'
include_recipe 'ark'

group node.riemann.group do
  action :create
end

user node.riemann.user do
  supports :manage_home => true
  home "/home/#{node.riemann.user}"
  group "#{node.riemann.group}" 
  action :create
  system true
  shell "/bin/bash"
  not_if "getent passwd #{node.riemann.user}"
end

group node.riemann.group do
  action :modify
   members ["#{node.riemann.user}"]
  append true
end

private_ip = node.private_ips[0]
public_ip = node.public_ips[0]

ark 'riemann' do
  url "#{node.riemann.download.url}riemann-#{node.riemann.download.version}.tar.bz2"
  version node.riemann.download.version
  checksum node.riemann.download.checksum
  owner node.riemann.user
  home_dir node.riemann.install_dir
  action :install
end

template node.riemann.config_file do
  owner node.riemann.user
  group node.riemann.group
  source 'riemann.config.erb'
  mode '0644'
end

file node.riemann.config_userfile do
  owner node.riemann.user
  group node.riemann.group
  action :create_if_missing
  mode '0644'
end

link '/etc/riemann' do
  to node.riemann.config_dir
end