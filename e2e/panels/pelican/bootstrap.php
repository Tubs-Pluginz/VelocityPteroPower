<?php
/**
 * Zero-touch headless bootstrap for live Pelican Panel (:latest, 1.0.x).
 * Runs inside the `panel` container (/var/www/html) to:
 *  1. Create root admin user via UserCreationService
 *  2. Mint real Application (papp_) + Client (pacc_) API keys
 *  3. Create Node (pointing to `wings:8080`) + Allocations (25601-25610)
 *  4. Import PicoLimbo VPP E2E Egg (PTDL_v2 -> Pelican 1.0 schema)
 *  5. Emit Pelican Wings YAML config + metadata JSON
 */

require '/var/www/html/vendor/autoload.php';
$app = require '/var/www/html/bootstrap/app.php';
$kernel = $app->make(Illuminate\Contracts\Console\Kernel::class);
$kernel->bootstrap();

use App\Models\Allocation;
use App\Models\ApiKey;
use App\Models\Egg;
use App\Models\EggVariable;
use App\Models\Node;
use App\Models\User;
use App\Services\Acl\Api\AdminAcl;
use App\Services\Users\UserCreationService;
use Illuminate\Support\Str;
use Symfony\Component\Yaml\Yaml;

try {
// 1. Admin user via Pelican's UserCreationService (assigns Super Admin role cleanly)
$user = User::where('email', 'admin@vpp.test')->first();
if (!$user) {
    /** @var UserCreationService $userCreator */
    $userCreator = app(UserCreationService::class);
    $user = $userCreator->handle([
        'email' => 'admin@vpp.test',
        'username' => 'admin',
        'password' => 'VppE2eAdminPassword123!',
        'root_admin' => true,
    ]);
}

// 2. Mint Application API key (papp_) — Pelican ApiKey casts `token` as `encrypted` automatically
$appIdentifier = ApiKey::generateTokenIdentifier(ApiKey::TYPE_APPLICATION);
$appPlain = Str::random(ApiKey::KEY_LENGTH);
$allPerms = [];
foreach (ApiKey::getPermissionList() as $resource) {
    $allPerms[$resource] = AdminAcl::READ | AdminAcl::WRITE;
}
$appKey = new ApiKey();
$appKey->user_id = $user->id;
$appKey->key_type = ApiKey::TYPE_APPLICATION;
$appKey->identifier = $appIdentifier;
$appKey->token = $appPlain;
$appKey->memo = 'VPP E2E Application Key';
$appKey->allowed_ips = [];
$appKey->permissions = $allPerms;
$appKey->save();
$fullAppKey = $appIdentifier . $appPlain;

// 3. Mint Client API key (pacc_) for VelocityPteroPower
$clientIdentifier = ApiKey::generateTokenIdentifier(ApiKey::TYPE_ACCOUNT);
$clientPlain = Str::random(ApiKey::KEY_LENGTH);
$clientKey = new ApiKey();
$clientKey->user_id = $user->id;
$clientKey->key_type = ApiKey::TYPE_ACCOUNT;
$clientKey->identifier = $clientIdentifier;
$clientKey->token = $clientPlain;
$clientKey->memo = 'VPP E2E Client Key';
$clientKey->allowed_ips = [];
$clientKey->save();
$fullClientKey = $clientIdentifier . $clientPlain;

// 4. Node pointing to `wings:8080` (Pelican 1.0 uses snake_case daemon_* and auto-encrypts daemon_token)
$wingsDataDir = getenv('WINGS_DATA_DIR') ?: '/var/lib/pelican';
$node = Node::where('name', 'vpp-e2e-pelican-wings')->first();
if (!$node) {
    $node = new Node();
    $node->uuid = (string) Str::uuid();
    $node->public = true;
    $node->name = 'vpp-e2e-pelican-wings';
    $node->description = 'Automated E2E Live Pelican Wings Node';
    $node->fqdn = 'wings';
    $node->scheme = 'http';
    $node->behind_proxy = false;
    $node->maintenance_mode = false;
    $node->memory = 8192;
    $node->memory_overallocate = 0;
    $node->disk = 32768;
    $node->disk_overallocate = 0;
    $node->cpu = 0;
    $node->cpu_overallocate = 0;
    $node->upload_size = 100;
    $node->daemon_token_id = Str::random(Node::DAEMON_TOKEN_ID_LENGTH);
    $node->daemon_token = Str::random(Node::DAEMON_TOKEN_LENGTH);
    $node->daemon_listen = 8080;
    $node->daemon_connect = 8080;
    $node->daemon_sftp = 2022;
    $node->daemon_base = $wingsDataDir . '/volumes';
    $node->save();
}

// 5. Allocations on 0.0.0.0:25601..25610
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

// 6. Import PicoLimbo VPP E2E Egg (Pelican 1.0 uses `startup_commands` array instead of `startup`)
$eggJson = json_decode(file_get_contents('/provision/egg-picolimbo-vpp-e2e.json'), true);
$egg = Egg::where('name', $eggJson['name'])->first();
if (!$egg) {
    $egg = new Egg();
    $egg->uuid = (string) Str::uuid();
    $egg->author = $eggJson['author'];
    $egg->name = $eggJson['name'];
    $egg->description = $eggJson['description'];
    $egg->docker_images = $eggJson['docker_images'];
    $egg->startup_commands = ['Default' => $eggJson['startup']];
    $egg->config_files = $eggJson['config']['files'];
    $egg->config_startup = $eggJson['config']['startup'];
    $egg->config_logs = $eggJson['config']['logs'];
    $egg->config_stop = $eggJson['config']['stop'];
    $egg->script_install = $eggJson['scripts']['installation']['script'];
    $egg->script_container = $eggJson['scripts']['installation']['container'];
    $egg->script_entry = $eggJson['scripts']['installation']['entrypoint'];
    $egg->file_denylist = $eggJson['file_denylist'] ?? [];
    $egg->tags = [];
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

$cfg = $node->getConfiguration();
$cfg['debug'] = true;
$cfg['remote'] = 'http://panel';
$cfg['system']['root_directory'] = $wingsDataDir;
$cfg['system']['data'] = $wingsDataDir . '/volumes';
$cfg['system']['archive_directory'] = $wingsDataDir . '/archives';
$cfg['system']['backup_directory'] = $wingsDataDir . '/backups';
$cfg['system']['user']['passwd']['enable'] = false;
$cfg['system']['machine_id']['enable'] = false;
$cfg['docker'] = [
    'network' => [
        'interface' => '172.29.0.1',
        'name' => 'vpp_pelican_wings_nw',
        'network_mode' => 'vpp_pelican_wings_nw',
        'driver' => 'bridge',
        'is_internal' => false,
        'enable_icc' => true,
        'interfaces' => [
            'v4' => [
                'subnet' => '172.29.0.0/16',
                'gateway' => '172.29.0.1',
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
