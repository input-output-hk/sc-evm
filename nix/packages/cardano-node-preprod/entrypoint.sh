
echo __argProjectStateDir__
echo ">>> $STATE"
mkdir -p "$STATE"
cd "$STATE" || exit 1

# exec cardano-node-preprod
