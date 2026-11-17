-- Reads of foreign tables whose scan does not run on all the segments:
-- mpp_execute 'master' (the WarehousePG 6 name, which WarehousePG 7 and 19
-- also accept for 'coordinator'), a wrapper that sets no mpp_execute (the
-- core default is the coordinator), and 'any' on its own, which the planner
-- runs on the coordinator.  These used to crash the coordinator before
-- reaching PXF.  The server points at a port nothing listens on, so every
-- read reaches PXF's connection error whether or not a PXF service runs.
-- The connection error's wording comes from libcurl and differs by version.
-- start_matchsubs
-- m/PXF server error : Failed to connect to .*/
-- s/PXF server error : Failed to connect to .*/PXF server error : Failed to connect to the PXF service/
-- end_matchsubs
SET client_min_messages = warning;
CREATE EXTENSION IF NOT EXISTS pxf_fdw;

CREATE SERVER pxf_fdw_el_server FOREIGN DATA WRAPPER file_pxf_fdw
    OPTIONS ( pxf_host '127.0.0.1', pxf_port '1024' );
CREATE SERVER pxf_fdw_el_master_server FOREIGN DATA WRAPPER file_pxf_fdw
    OPTIONS ( pxf_host '127.0.0.1', pxf_port '1024', mpp_execute 'master' );
CREATE FOREIGN DATA WRAPPER pxf_fdw_el_pxf_fdw
    HANDLER pxf_fdw_handler
    VALIDATOR pxf_fdw_validator
    OPTIONS ( protocol 'file' );
CREATE SERVER pxf_fdw_el_default_server FOREIGN DATA WRAPPER pxf_fdw_el_pxf_fdw
    OPTIONS ( pxf_host '127.0.0.1', pxf_port '1024' );
CREATE USER MAPPING FOR CURRENT_USER SERVER pxf_fdw_el_server;
CREATE USER MAPPING FOR CURRENT_USER SERVER pxf_fdw_el_master_server;
CREATE USER MAPPING FOR CURRENT_USER SERVER pxf_fdw_el_default_server;
RESET client_min_messages;

-- ===================================================================
-- A read that runs on the coordinator reaches PXF
-- ===================================================================

--
-- mpp_execute on the table
--
CREATE FOREIGN TABLE pxf_fdw_el_master (id int, name text)
    SERVER pxf_fdw_el_server
    OPTIONS ( resource 'data.txt', format 'text', mpp_execute 'master' );
SELECT * FROM pxf_fdw_el_master;

--
-- mpp_execute on the server, and written in upper case
--
CREATE FOREIGN TABLE pxf_fdw_el_master_from_server (id int, name text)
    SERVER pxf_fdw_el_master_server
    OPTIONS ( resource 'data.txt', format 'text' );
SELECT * FROM pxf_fdw_el_master_from_server;
CREATE FOREIGN TABLE pxf_fdw_el_master_upper (id int, name text)
    SERVER pxf_fdw_el_server
    OPTIONS ( resource 'data.txt', format 'text', mpp_execute 'MASTER' );
SELECT * FROM pxf_fdw_el_master_upper;

--
-- no mpp_execute on the table, the server or the wrapper
--
CREATE FOREIGN TABLE pxf_fdw_el_default (id int, name text)
    SERVER pxf_fdw_el_default_server
    OPTIONS ( resource 'data.txt', format 'text' );
SELECT * FROM pxf_fdw_el_default;

--
-- mpp_execute 'any' on its own runs on the coordinator
--
CREATE FOREIGN TABLE pxf_fdw_el_any (id int, name text)
    SERVER pxf_fdw_el_server
    OPTIONS ( resource 'data.txt', format 'text', mpp_execute 'any' );
SELECT * FROM pxf_fdw_el_any;

--
-- the same reads with the other optimizer
--
SET optimizer = off;
SELECT * FROM pxf_fdw_el_master;
SELECT * FROM pxf_fdw_el_default;
SELECT * FROM pxf_fdw_el_any;
RESET optimizer;

--
-- EXPLAIN without ANALYZE does not read
--
DO $$ BEGIN EXECUTE 'EXPLAIN SELECT * FROM pxf_fdw_el_master'; END $$;
DO $$ BEGIN EXECUTE 'EXPLAIN SELECT * FROM pxf_fdw_el_any'; END $$;

-- ===================================================================
-- log_errors needs mpp_execute 'all segments'
-- ===================================================================

--
-- refused before PXF is contacted, wherever mpp_execute is set
--
CREATE FOREIGN TABLE pxf_fdw_el_master_log (id int, name text)
    SERVER pxf_fdw_el_server
    OPTIONS ( resource 'data.txt', format 'text', mpp_execute 'master',
              reject_limit '10', log_errors 'true' );
SELECT * FROM pxf_fdw_el_master_log;
CREATE FOREIGN TABLE pxf_fdw_el_any_log (id int, name text)
    SERVER pxf_fdw_el_server
    OPTIONS ( resource 'data.txt', format 'text', mpp_execute 'any',
              reject_limit '10', log_errors 'true' );
SELECT * FROM pxf_fdw_el_any_log;
CREATE FOREIGN TABLE pxf_fdw_el_server_log (id int, name text)
    SERVER pxf_fdw_el_master_server
    OPTIONS ( resource 'data.txt', format 'text',
              reject_limit '10', log_errors 'true' );
SELECT * FROM pxf_fdw_el_server_log;

--
-- a reject limit without log_errors is allowed
--
CREATE FOREIGN TABLE pxf_fdw_el_master_reject (id int, name text)
    SERVER pxf_fdw_el_server
    OPTIONS ( resource 'data.txt', format 'text', mpp_execute 'master',
              reject_limit '10' );
SELECT * FROM pxf_fdw_el_master_reject;

--
-- log_errors 'false' is allowed
--
ALTER FOREIGN TABLE pxf_fdw_el_master_log OPTIONS ( SET log_errors 'false' );
SELECT * FROM pxf_fdw_el_master_log;

--
-- the session is still usable
--
SELECT 1 AS still_connected;

SET client_min_messages = warning;
DROP SERVER pxf_fdw_el_server CASCADE;
DROP SERVER pxf_fdw_el_master_server CASCADE;
DROP FOREIGN DATA WRAPPER pxf_fdw_el_pxf_fdw CASCADE;
RESET client_min_messages;
