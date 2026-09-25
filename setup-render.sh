#!/bin/bash
# ============================================================
# Script de preparación para despliegue en Render
# ============================================================
# Este script prepara tu proyecto para desplegar en Render Free Tier
# ============================================================

set -e

echo "🚀 Preparando proyecto para Render Free Tier..."
echo ""

# Colores
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

# Verificar que estamos en el directorio correcto
if [ ! -f "Dockerfile" ] || [ ! -f "render.yaml" ]; then
    echo -e "${RED}❌ Error: No se encontró Dockerfile o render.yaml${NC}"
    echo "Ejecuta este script desde la raíz del proyecto"
    exit 1
fi

echo -e "${GREEN}✓ Directorio correcto${NC}"

# Verificar que server/ existe
if [ ! -d "server" ]; then
    echo -e "${RED}❌ Error: No se encontró el directorio server/${NC}"
    exit 1
fi

echo -e "${GREEN}✓ Directorio server/ encontrado${NC}"

# Verificar archivos del servidor
REQUIRED_FILES=(
    "server/server.py"
    "server/download_engine.py"
    "server/models/song.py"
    "server/utils/helpers.py"
)

for file in "${REQUIRED_FILES[@]}"; do
    if [ ! -f "$file" ]; then
        echo -e "${RED}❌ Error: Falta archivo $file${NC}"
        exit 1
    fi
done

echo -e "${GREEN}✓ Todos los archivos del servidor presentes${NC}"

# Verificar que Docker está instalado
if command -v docker &> /dev/null; then
    echo -e "${GREEN}✓ Docker instalado${NC}"
    
    # Probar build local
    echo ""
    echo "🔨 Probando build local..."
    if docker build -t mp3downloader-test .; then
        echo -e "${GREEN}✓ Build exitoso${NC}"
        
        # Preguntar si quiere probar localmente
        echo ""
        read -p "¿Quieres probar el servidor localmente? (s/n): " -n 1 -r
        echo ""
        
        if [[ $REPLY =~ ^[Ss]$ ]]; then
            echo "🚀 Iniciando servidor local..."
            docker run -d \
                --name mp3downloader-test \
                -p 8899:8899 \
                mp3downloader-test
            
            echo ""
            echo "✅ Servidor iniciado en http://localhost:8899"
            echo "📊 Health check: http://localhost:8899/api/health"
            echo ""
            echo "Para detener: docker stop mp3downloader-test && docker rm mp3downloader-test"
        fi
    else
        echo -e "${YELLOW}⚠️  Build falló. Revisa el Dockerfile${NC}"
    fi
else
    echo -e "${YELLOW}⚠️  Docker no instalado (no es obligatorio)${NC}"
fi

# Verificar git
if command -v git &> /dev/null; then
    echo ""
    echo "📋 Estado de Git:"
    
    # Verificar si hay cambios sin commitear
    if [ -n "$(git status --porcelain)" ]; then
        echo -e "${YELLOW}⚠️  Hay cambios sin commitear:${NC}"
        git status --short
        
        echo ""
        read -p "¿Quieres commitear los cambios? (s/n): " -n 1 -r
        echo ""
        
        if [[ $REPLY =~ ^[Ss]$ ]]; then
            git add .
            git commit -m "Preparar despliegue en Render Free Tier"
            echo -e "${GREEN}✓ Cambios commiteados${NC}"
        fi
    else
        echo -e "${GREEN}✓ No hay cambios pendientes${NC}"
    fi
    
    # Verificar remote
    REMOTE=$(git remote get-url origin 2>/dev/null || echo "none")
    if [ "$REMOTE" = "none" ]; then
        echo -e "${YELLOW}⚠️  No hay remote configurado${NC}"
        echo "Configura tu repositorio de GitHub:"
        echo "  git remote add origin https://github.com/tu-usuario/tu-repo.git"
    else
        echo -e "${GREEN}✓ Remote: $REMOTE${NC}"
    fi
fi

# Crear .gitignore si no existe
if [ ! -f ".gitignore" ]; then
    echo ""
    echo "📝 Creando .gitignore..."
    cat > .gitignore << 'EOF'
# Python
__pycache__/
*.py[cod]
*$py.class
*.so
.Python
build/
develop-eggs/
dist/
downloads/
eggs/
.eggs/
lib/
lib64/
parts/
sdist/
var/
wheels/
*.egg-info/
.installed.cfg
*.egg

# Virtual Environment
venv/
ENV/
env/

# IDE
.vscode/
.idea/
*.swp
*.swo

# OS
.DS_Store
Thumbs.db

# Logs
*.log
logs/

# Cookies (sensitive)
cookies.txt
*.cookies

# Temp files
*.tmp
*.temp
EOF
    echo -e "${GREEN}✓ .gitignore creado${NC}"
fi

echo ""
echo "============================================================"
echo -e "${GREEN}✅ ¡Proyecto listo para Render!${NC}"
echo "============================================================"
echo ""
echo "📋 Próximos pasos:"
echo ""
echo "1. Sube tu código a GitHub:"
echo "   git push origin main"
echo ""
echo "2. Ve a render.com y crea una cuenta gratuita"
echo ""
echo "3. Crea un nuevo 'Web Service' con:"
echo "   - Runtime: Docker"
echo "   - Dockerfile Path: ./Dockerfile"
echo "   - Port: 8899"
echo ""
echo "4. Tu servidor estará en:"
echo "   https://mp3downloader-server.onrender.com"
echo ""
echo "5. Configura el cliente Android con la nueva URL"
echo ""
echo "📖 Lee DEPLOY-RENDER.md para más detalles"
echo ""
echo "============================================================"
