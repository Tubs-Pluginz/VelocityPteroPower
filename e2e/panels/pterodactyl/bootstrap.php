<?php
/**
 * Zero-touch headless bootstrap for live Pterodactyl Panel (:latest).
 * Runs inside the `panel` container to:
 *  1. Create root admin user
 *  2. Mint real Application (ptla_) + Client (ptlc_) API keys
 *  3. Create Location, Node (pointing to `wings:8080`), and Allocations (25601-25610)
 *  4. Import PicoLimbo VPP E2E Egg (PTDL_v2)
 *  5. Emit Wings YAML config + bootstrap metadata JSON to stdout
 */

require '/app/vendor/autoload.php';
$app = require '/app/bootstrap/app.php';
$kernel = $app->make(Illuminate\Contracts\Console\Kernel::class);
$kernel->bootstrap();

use Illuminate\Contracts\Encryption\Encrypter;
use Illuminate\Support\Facades\Hash;
use Illuminate\Support\Str;
use Pterodactyl\Models\Allocation;
use Pterodactyl\Models\ApiKey;
use Pterodactyl\Models\Egg;
use Pterodactyl\Models\EggVariable;
use Pterodactyl\Models\Location;
use Pterodactyl\Models\Nest;
use Pterodactyl\Models\Node;
use Pterodactyl\Models\User;
use Pterodactyl\Services\Acl\Api\AdminAcl;
use Symfony\Component\Yaml\Yaml;

try {
$encrypter = app(Encrypter::class);

// 1. Admin user (assign directly because uuid/root_admin are not mass-assignable)
$user = User::where('email', 'admin@vpp.test')->first();
if (!$user) {
    $user = new User();
    $user->uuid = (string) Str::uuid();
    $user->email = 'admin@vpp.test';
    $user->username = 'admin';
    $user->name_first = 'VPP';
    $user->name_last = 'Admin';
    $user->password = Hash::make('VppE2eAdminPassword123!');
    $user->root_admin = true;
    $user->language = 'en';
    $user->save();
}

// 2. Mint Application API key (ptla_) with full RW permissions
$appIdentifier = ApiKey::generateTokenIdentifier(ApiKey::TYPE_APPLICATION);
$appPlain = Str::random(ApiKey::KEY_LENGTH);
$appKey = new ApiKey();
$appKey->user_id = $user->id;
$appKey->key_type = ApiKey::TYPE_APPLICATION;
$appKey->identifier = $appIdentifier;
$appKey->token = $encrypter->encrypt($appPlain);
$appKey->memo = 'VPP E2E Application Key';
$appKey->allowed_ips = [];
foreach ([
    'r_servers', 'r_nodes', 'r_allocations', 'r_users',
    'r_locations', 'r_nests', 'r_eggs', 'r_database_hosts', 'r_server_databases'
] as $col) {
    $appKey->{$col} = AdminAcl::READ | AdminAcl::WRITE;
}
$appKey->save();
$fullAppKey = $appIdentifier . $appPlain;

// 3. Mint Client API key (ptlc_) for VelocityPteroPower
$clientIdentifier = ApiKey::generateTokenIdentifier(ApiKey::TYPE_ACCOUNT);
$clientPlain = Str::random(ApiKey::KEY_LENGTH);
$clientKey = new ApiKey();
$clientKey->user_id = $user->id;
$clientKey->key_type = ApiKey::TYPE_ACCOUNT;
$clientKey->identifier = $clientIdentifier;
$clientKey->token = $encrypter->encrypt($clientPlain);
$clientKey->memo = 'VPP E2E Client Key';
$clientKey->allowed_ips = [];
$clientKey->save();
$fullClientKey = $clientIdentifier . $clientPlain;

// 4. Location
$location = Location::where('short', 'e2e')->first();
if (!$location) {
    $location = new Location();
    $location->short = 'e2e';
    $location->long = 'VPP Automated E2E Location';
    $location->save();
}

// 5. Node pointing to `wings` container on Docker Compose network (HTTP for zero-cert CI)
$node = Node::where('name', 'vpp-e2e-wings')->first();
if (!$node) {
    $node = new Node();
    $node->uuid = (string) Str::uuid();
    $node->public = true;
    $node->name = 'vpp-e2e-wings';
    $node->description = 'Automated E2E Live Wings Node';
    $node->location_id = $location->id;
    $node->fqdn = 'wings';
    $node->scheme = 'http';
    $node->behind_proxy = false;
    $node->maintenance_mode = false;
    $node->memory = 8192;
    $node->memory_overallocate = 0;
    $node->disk = 32768;
    $node->disk_overallocate = 0;
    $node->upload_size = 100;
    $node->daemon_token_id = Str::random(Node::DAEMON_TOKEN_ID_LENGTH);
    $node->daemon_token = $encrypter->encrypt(Str::random(Node::DAEMON_TOKEN_LENGTH));
    $node->daemonListen = 8080;
    $node->daemonSFTP = 2022;
    $node->daemonBase = (getenv('WINGS_DATA_DIR') ?: '/var/lib/pterodactyl') . '/volumes';
    $node->save();
}

// 6. Allocations on 0.0.0.0:25601..25610
$allocations = [];
for ($port = 25601; $port <= 25610; $port++) {
    $alloc = Allocation::where('node_id', $node->id)->where('ip', '0.0.0.0')->where('port', $port)->first();
    if (!$alloc) {
        $alloc = new Allocation();
        $alloc->node_id = $node->id;
        $alloc->ip = '0.0.0.0';
        $alloc->port = $port;
        $alloc->ip_alias = 'host.docker.internal';
        $alloc->save();
    }
    $allocations[(string) $port] = $alloc->id;
}

// 7. Import PicoLimbo VPP E2E Egg
$eggJson = json_decode(file_get_contents('/provision/egg-picolimbo-vpp-e2e.json'), true);
$nest = Nest::where('name', 'VPP E2E')->first();
if (!$nest) {
    $nest = new Nest();
    $nest->uuid = (string) Str::uuid();
    $nest->author = 'vpp-e2e@pluginz.dev';
    $nest->name = 'VPP E2E';
    $nest->description = 'Automated E2E test nest';
    $nest->save();
}

$egg = Egg::where('nest_id', $nest->id)->where('name', $eggJson['name'])->first();
if (!$egg) {
    $egg = new Egg();
    $egg->uuid = (string) Str::uuid();
    $egg->nest_id = $nest->id;
    $egg->author = $eggJson['author'];
    $egg->name = $eggJson['name'];
    $egg->description = $eggJson['description'];
    $egg->docker_images = $eggJson['docker_images'];
    $egg->startup = $eggJson['startup'];
    $egg->config_files = $eggJson['config']['files'];
    $egg->config_startup = $eggJson['config']['startup'];
    $egg->config_logs = $eggJson['config']['logs'];
    $egg->config_stop = $eggJson['config']['stop'];
    $egg->script_install = $eggJson['scripts']['installation']['script'];
    $egg->script_container = $eggJson['scripts']['installation']['container'];
    $egg->script_entry = $eggJson['scripts']['installation']['entrypoint'];
    $egg->file_denylist = $eggJson['file_denylist'] ?? [];
    $egg->save();

    foreach ($eggJson['variables'] ?? [] as $var) {
        $ev = new EggVariable();
        $ev->egg_id = $egg->id;
        $ev->name = $var['name'];
        $ev->description = $var['description'];
        $ev->env_variable = $var['env_variable'];
        $ev->default_value = $var['default_value'];
        $ev->user_viewable = $var['user_viewable'];
        $ev->user_editable = $var['user_editable'];
        $ev->rules = $var['rules'];
        $ev->save();
    }
}

$wingsDataDir = getenv('WINGS_DATA_DIR') ?: '/var/lib/pterodactyl';
$cfg = $node->getConfiguration();
$cfg['debug'] = true;
$cfg['remote'] = 'http://panel';
$cfg['system']['root_directory'] = $wingsDataDir;
$cfg['system']['data'] = $wingsDataDir . '/volumes';
$cfg['system']['archive_directory'] = $wingsDataDir . '/archives';
$cfg['system']['backup_directory'] = $wingsDataDir . '/backups';
$cfg['docker'] = [
    'network' => [
        'interface' => '172.28.0.1',
        'name' => 'vpp_ptero_wings_nw',
        'network_mode' => 'vpp_ptero_wings_nw',
        'driver' => 'bridge',
        'is_internal' => false,
        'enable_icc' => true,
        'interfaces' => [
            'v4' => [
                'subnet' => '172.28.0.0/16',
                'gateway' => '172.28.0.1',
            ],
        ],
    ],
];
$wingsYaml = Yaml::dump($cfg, 5, 2, Yaml::DUMP_EMPTY_ARRAY_AS_SEQUENCE);

echo json_encode([
    'panelVersion' => (string) config('app.version', 'unknown'),
    'userId' => $user->id,
    'nodeId' => $node->id,
    'eggId' => $egg->id,
    'dockerImage' => array_values($eggJson['docker_images'])[0],
    'startup' => $eggJson['startup'],
    'appKey' => $fullAppKey,
    'clientKey' => $fullClientKey,
    'allocations' => $allocations,
    'wingsConfigYaml' => $wingsYaml,
], JSON_UNESCAPED_SLASHES) . PHP_EOL;
} catch (\Throwable $e) {
    fwrite(STDERR, "[bootstrap-error] " . $e->getMessage() . PHP_EOL . $e->getTraceAsString() . PHP_EOL);
    exit(1);
}
